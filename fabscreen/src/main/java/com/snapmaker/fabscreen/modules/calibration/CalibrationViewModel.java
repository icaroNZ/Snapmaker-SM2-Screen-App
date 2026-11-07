package com.snapmaker.fabscreen.modules.calibration;

import android.annotation.SuppressLint;
import android.os.SystemClock;

import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import fabscreen.libraries.legacy.base.BaseViewModel;
import fabscreen.libraries.legacy.data.Constants;
import fabscreen.libraries.legacy.data.ISlaveComputer;
import fabscreen.libraries.legacy.data.calibration.CalibrationPoint;
import fabscreen.libraries.legacy.data.calibration.HeatSoakTracker;
import fabscreen.libraries.legacy.data.calibration.LevelingParams;
import fabscreen.libraries.legacy.data.calibration.ZOffsetParams;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacketContent;
import fabscreen.libraries.legacy.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;

/**
 * We manage the lifecycle of this ViewModel by ourself, instead of AutoDispose.
 */
@SuppressLint("AutoDispose")
public class CalibrationViewModel extends BaseViewModel {

    private final static int[][] mDirection = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}}; // right, up, left, down, rotate with counterclockwise
    private int mCurrentDir = 0;

    private int mGrid;
    private boolean mIsAutoMode;
    private boolean mIsHeatedLevelingOn;
    private ArrayList<CalibrationPoint> mPoints;
    private CalibrationPoint mCurrentPoint;
    private int mCalibratedPointCount = 0;
    private float mHeatedLevelingTemperature = 0;

    // Heat soak done by the controller before measuring (firmware SM2-5.2.1-a250t.3+). 0 = no heating.
    private int mSoakBedTemperature;
    private int mSoakMinutes;
    private boolean mUserCancelled = false;
    private HeatSoakTracker mHeatSoakTracker = new HeatSoakTracker(0, 0);
    private Disposable mHeatSoakTicker;
    private BehaviorSubject<HeatSoakStatus> mHeatSoakStatusSubject =
            BehaviorSubject.createDefault(new HeatSoakStatus(HeatSoakTracker.PHASE_DONE, 0, 0, 0, 0));

    // Separate bed mesh / Z-offset calibration (controller SM2-5.2.1-a250t.4+, single 3DP head, auto mode).
    public static final int MODE_MESH = 0;
    public static final int MODE_ZOFFSET = 1;
    private static final int VERSION_TIMEOUT_MS = 3000;
    private static final int ZOFFSET_QUERY_TIMEOUT_MS = 3000;
    private int mMode = MODE_MESH;
    // Set only from the calibration menu once the controller answered the Z offset query, so other users of
    // this ViewModel (3DP guide, preview) keep the stock card-step flow.
    private boolean mUseStoredZOffset = false;
    private boolean mZOffsetCheckStarted = false;
    private BehaviorSubject<ZOffsetSupport> mZOffsetSupportSubject = BehaviorSubject.createDefault(ZOffsetSupport.CHECKING);
    private PublishSubject<ZOffsetParams.StatusReply> mMeshResultSubject = PublishSubject.create();

    private BehaviorSubject<Boolean> mAutoZOffsetSubject = BehaviorSubject.createDefault(false);
    private BehaviorSubject<Boolean> mIsAdjustZOffsetSubject = BehaviorSubject.createDefault(false);
    private BehaviorSubject<Boolean> mIsMovingSubject = BehaviorSubject.createDefault(false);
    private BehaviorSubject<Boolean> mUpdatePointsSubject = BehaviorSubject.createDefault(false);
    private PublishSubject<Boolean> mAutoCalibrationResultSubject = PublishSubject.create();

    private final CompositeDisposable mDisposables = new CompositeDisposable();

    public CalibrationViewModel() {
        super();

        int calibrationMode = getModel().getPreferences().get3DPCalibrationMode();
        mIsAutoMode = (calibrationMode == 0);

        mIsHeatedLevelingOn = getModel().getPreferences().get3DPCalibrationHeatedLevelingOn();

        mGrid = LevelingParams.clampGrid(getModel().getPreferences().get3DPCalibrationGrid());

        mSoakBedTemperature = getModel().getPreferences().get3DPCalibrationSoakBedTemperature();
        mSoakMinutes = getModel().getPreferences().get3DPCalibrationSoakMinutes();
    }

    /**
     * Snapshot of the heat soak for the progress overlay.
     */
    public static class HeatSoakStatus {
        public final int phase;
        public final int bedTemperature;
        public final int targetTemperature;
        public final int soakSeconds;
        public final int secondsLeft;

        HeatSoakStatus(int phase, int bedTemperature, int targetTemperature, int soakSeconds, int secondsLeft) {
            this.phase = phase;
            this.bedTemperature = bedTemperature;
            this.targetTemperature = targetTemperature;
            this.soakSeconds = soakSeconds;
            this.secondsLeft = secondsLeft;
        }

        public boolean isActive() {
            return phase != HeatSoakTracker.PHASE_DONE;
        }
    }

    /**
     * Whether the controller offers the separate Z-offset calibration, and the stored offset.
     */
    public static class ZOffsetSupport {
        static final ZOffsetSupport CHECKING = new ZOffsetSupport(true, null);
        static final ZOffsetSupport UNSUPPORTED = new ZOffsetSupport(false, null);

        public final boolean checking;
        /** Non-null when the controller answered 0x09 0x38. */
        public final ZOffsetParams.StatusReply reply;

        ZOffsetSupport(boolean checking, ZOffsetParams.StatusReply reply) {
            this.checking = checking;
            this.reply = reply;
        }

        public boolean isSupported() {
            return reply != null;
        }
    }

    /**
     * Calibration menu: find out once whether the controller supports the separate Z-offset calibration
     * (version SM2-5.2.1-a250t.4+ and an answer to the 0x09 0x38 query). Anything else (older or stock
     * firmware, no answer, dual extruder, manual mode) keeps the stock flow.
     */
    public void checkZOffsetSupport() {
        if (mZOffsetCheckStarted) {
            return;
        }
        mZOffsetCheckStarted = true;

        final int headType = getModel().getMachineController().getHeadType();
        if (!mIsAutoMode || headType != Constants.HEAD_3DP) {
            Logger.i("Leveling: Z offset calibration not offered (auto %b, head type %d), stock flow",
                    mIsAutoMode, headType);
            mZOffsetSupportSubject.onNext(ZOffsetSupport.UNSUPPORTED);
            return;
        }

        final ISlaveComputer slave = getModel().getSlaveComputer();
        mDisposables.add(slave.getControllerVersion()
                .timeout(VERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .flatMap(version -> {
                    if (!slave.isZOffsetCalibrationSupported(version)) {
                        Logger.i("Leveling: controller %s has no Z offset calibration, stock flow", version);
                        return Observable.just(ZOffsetSupport.UNSUPPORTED);
                    }
                    Logger.i("Leveling: controller %s, querying the Z offset", version);
                    return slave.queryZOffset(ZOFFSET_QUERY_TIMEOUT_MS)
                            .map(reply -> new ZOffsetSupport(false, reply));
                })
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(support -> {
                    if (support.isSupported()) {
                        Logger.i("Leveling: Z offset %s", support.reply);
                    }
                    mZOffsetSupportSubject.onNext(support);
                }, e -> {
                    Logger.w("Leveling: Z offset query failed (%s), stock flow", e.toString());
                    mZOffsetSupportSubject.onNext(ZOffsetSupport.UNSUPPORTED);
                }));
    }

    public Observable<ZOffsetSupport> getZOffsetSupportObservable() {
        return mZOffsetSupportSubject.hide();
    }

    /**
     * "Measure bed mesh" (or the stock Start button): with a supporting controller, ask it to apply the
     * stored Z offset so a valid offset skips the card step.
     */
    public void selectMeshMeasurement() {
        final ZOffsetSupport support = mZOffsetSupportSubject.getValue();
        mMode = MODE_MESH;
        mUseStoredZOffset = support != null && support.isSupported();
        Logger.i("Leveling: menu -> measure bed mesh, use stored Z offset %b", mUseStoredZOffset);
    }

    /**
     * "Calibrate Z offset": centre probe + card step + save (0x09 0x37, 0x06, 0x07).
     */
    public void selectZOffsetCalibration() {
        mMode = MODE_ZOFFSET;
        mUseStoredZOffset = false;
        Logger.i("Leveling: menu -> calibrate Z offset");
    }

    public boolean isZOffsetMode() {
        return mMode == MODE_ZOFFSET;
    }

    public boolean isUseStoredZOffset() {
        return mUseStoredZOffset;
    }

    /**
     * Auto levelling replies when the stored offset was requested: applied (finished) or card step needed.
     */
    public Observable<ZOffsetParams.StatusReply> getMeshResultObservable() {
        return mMeshResultSubject.hide();
    }

    /**
     * Read the stored Z offset (e.g. after saving a Z-offset calibration).
     */
    public Observable<ZOffsetParams.StatusReply> queryZOffset() {
        return getModel().getSlaveComputer().queryZOffset(ZOFFSET_QUERY_TIMEOUT_MS);
    }

    /**
     * Options chosen in the levelling options dialog; remembered for next time.
     */
    public void setLevelingOptions(int grid, int bedTemperature, int soakMinutes) {
        mGrid = LevelingParams.clampGrid(grid);
        mSoakBedTemperature = LevelingParams.clampBedTemperature(bedTemperature);
        mSoakMinutes = LevelingParams.clampSoakMinutes(soakMinutes);

        getModel().getPreferences().set3DPCalibrationGrid(mGrid);
        getModel().getPreferences().set3DPCalibrationSoakBedTemperature(mSoakBedTemperature);
        getModel().getPreferences().set3DPCalibrationSoakMinutes(mSoakMinutes);

        Logger.i("Leveling: options %s, grid %d, bed %d C, soak %d min", mIsAutoMode ? "auto" : "manual",
                mGrid, mSoakBedTemperature, mSoakMinutes);
    }

    public int getSoakBedTemperature() {
        return mSoakBedTemperature;
    }

    public int getSoakMinutes() {
        return mSoakMinutes;
    }

    /**
     * True if the controller heats the bed before measuring (so the bed must be turned off afterwards).
     */
    public boolean isHeatSoakUsed() {
        return mSoakBedTemperature > 0;
    }

    public Observable<HeatSoakStatus> getHeatSoakStatusObservable() {
        return mHeatSoakStatusSubject.hide();
    }

    private void emitHeatSoakStatus() {
        final long now = SystemClock.elapsedRealtime();
        mHeatSoakStatusSubject.onNext(new HeatSoakStatus(mHeatSoakTracker.getPhase(),
                mHeatSoakTracker.getBedTemp(), mHeatSoakTracker.getTargetTemp(),
                mHeatSoakTracker.getSoakSeconds(), mHeatSoakTracker.getSecondsLeft(now)));
    }

    private void startHeatSoakTracking(int soakSeconds) {
        stopHeatSoakTicker();
        mHeatSoakTracker = new HeatSoakTracker(mSoakBedTemperature, soakSeconds);
        if (!mHeatSoakTracker.isActive()) {
            emitHeatSoakStatus();
            return;
        }

        Logger.i("Leveling: heat soak started, bed %d C, soak %d s", mSoakBedTemperature, soakSeconds);
        // The controller sends no progress while heating/soaking: follow the bed temperature each second.
        mHeatSoakTicker = Observable.interval(0, 1, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tick -> {
                    FabPacketContent.MachineStatus status = getModel().getSlaveComputer().getMachineStatus();
                    final int bed = status != null ? status.bedTemperature : 0;
                    if (mHeatSoakTracker.update(bed, SystemClock.elapsedRealtime())) {
                        Logger.i("Leveling: heat soak phase %d, bed %d / %d C", mHeatSoakTracker.getPhase(),
                                bed, mHeatSoakTracker.getTargetTemp());
                    }
                    emitHeatSoakStatus();
                }, LogHelper::log);
        mDisposables.add(mHeatSoakTicker);
        emitHeatSoakStatus();
    }

    private void finishHeatSoakTracking(String reason) {
        if (mHeatSoakTracker.isActive()) {
            Logger.i("Leveling: heat soak display ended (%s), phase was %d", reason, mHeatSoakTracker.getPhase());
            mHeatSoakTracker.finish();
        }
        stopHeatSoakTicker();
        emitHeatSoakStatus();
    }

    private void stopHeatSoakTicker() {
        if (mHeatSoakTicker != null) {
            mHeatSoakTicker.dispose();
            mDisposables.delete(mHeatSoakTicker);
            mHeatSoakTicker = null;
        }
    }

    /**
     * User cancelled while the controller heats/soaks. Drops the pending start request first, so its
     * failure reply (err 1 after the exit event aborts the soak) does not show the failure dialog.
     * The caller then sends exit levelling (0x09 0x08), which aborts the soak in the controller.
     */
    public void cancelLeveling() {
        Logger.i("Leveling: cancelled by user, heat soak phase %d", mHeatSoakTracker.getPhase());
        mUserCancelled = true;
        mDisposables.clear();
        mHeatSoakTicker = null;
        finishHeatSoakTracking("cancel");
    }

    public boolean isUserCancelled() {
        return mUserCancelled;
    }

    public Observable<Boolean> getIsMovingObservable() {
        return mIsMovingSubject.hide();
    }

    public int getGridCount() {
        return mGrid;
    }

    public Observable<Boolean> getUpdatePointsObservable() {
        return mUpdatePointsSubject.hide();
    }

    public boolean isAdjustZOffset() {
        //noinspection ConstantConditions
        return mIsAdjustZOffsetSubject != null && mIsAdjustZOffsetSubject.getValue();
    }

    public Observable<Boolean> getOffsetObservable() {
        return mAutoZOffsetSubject.hide();
    }

    public Observable<Boolean> getAutoCalibrationResultObservable() {
        return mAutoCalibrationResultSubject.hide();
    }

    public boolean isAutoMode() {
        return mIsAutoMode;
    }

    public void setAutoMode(boolean isAutoMode) {
        mIsAutoMode = isAutoMode;
    }

    public boolean isHeatedLevelingOn() {
        return mIsHeatedLevelingOn;
    }

    public void setHeatedLevelingTemperature(float temperature) {
        mHeatedLevelingTemperature = temperature;
    }

    public float getHeatedLevelingTemperature() {
        return mHeatedLevelingTemperature;
    }

    public void turnOffBed() {
        // Turn off heated bed using M140 G-code.
        mDisposables.add(getModel().getSlaveComputer()
                .sendGcode("M140 S0")
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(response -> {/**/}, LogHelper::log));
    }

    public void startCalibration() {
        if (mMode == MODE_ZOFFSET) {
            startZOffsetCalibration();
            return;
        }
        mUserCancelled = false;
        mIsMovingSubject.onNext(true);

        final int soakSeconds = LevelingParams.soakMinutesToSeconds(mSoakMinutes);
        Logger.i("Leveling: start %s, grid %d, bed %d C, soak %d s", mIsAutoMode ? "auto" : "manual",
                mGrid, mSoakBedTemperature, soakSeconds);
        startHeatSoakTracking(soakSeconds);

        if (mIsAutoMode) {
            // listen auto calibration progress
            mDisposables.add(getModel().getSlaveComputer()
                    .getAutoCalibrationProgress()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(order -> {
                        Logger.d("Point %d calibrated.", order);
                        // the first probed point means heating/soaking is over
                        finishHeatSoakTracking("probing");
                        mCalibratedPointCount++;

                        moveToNextPoint();
                        mUpdatePointsSubject.onNext(true);
                    }));

            if (mUseStoredZOffset) {
                startAutoCalibrationWithStoredZOffset(soakSeconds);
            } else {
                mDisposables.add(getModel().getSlaveComputer()
                        .startAutoCalibration(mGrid, mSoakBedTemperature, soakSeconds)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(completed -> {
                            finishHeatSoakTracking("auto reply");
                            if (mUserCancelled) {
                                Logger.i("Leveling: auto reply after cancel ignored");
                                return;
                            }
                            Logger.i("Leveling: auto levelling %s", completed ? "done" : "failed");
                            if (completed) {
                                // Set ZOffset after auto calibration completed.
                                mAutoZOffsetSubject.onNext(true);
                                mCurrentPoint.setActivated(false);
                                mIsMovingSubject.onNext(false);
                            } else {
                                // Auto calibration fail.
                                mIsMovingSubject.onNext(false);
                                mAutoCalibrationResultSubject.onNext(false);
                            }
                        }, e -> {
                            LogHelper.log(e);
                            finishHeatSoakTracking("auto error");
                            mIsMovingSubject.onNext(false);
                            mAutoCalibrationResultSubject.onNext(false);
                        }));
            }

            // start with the corner
            mCurrentPoint = getPoint(mGrid, 1);
        } else {
            mDisposables.add(getModel().getSlaveComputer()
                    .startManualCalibration(mGrid, mSoakBedTemperature, soakSeconds)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(completed -> {
                        finishHeatSoakTracking("manual reply");
                        mIsMovingSubject.onNext(false);
                        if (mUserCancelled) {
                            Logger.i("Leveling: manual reply after cancel ignored");
                            return;
                        }
                        if (!completed) {
                            Logger.w("Leveling: manual levelling could not start");
                            mAutoCalibrationResultSubject.onNext(false);
                            return;
                        }
                        // start with the corner
                        mCurrentPoint = getPoint(mGrid, 1);
                        if (mCurrentPoint != null) {
                            gotoPointManual(mCurrentPoint.getCoordinateOrder());
                        }
                    }, e -> {
                        LogHelper.log(e);
                        finishHeatSoakTracking("manual error");
                        mIsMovingSubject.onNext(false);
                    }));
        }
        mUpdatePointsSubject.onNext(true);
    }

    /**
     * Auto levelling asking the controller to apply the stored Z offset (0x09 0x02 flags bit 0). A valid
     * offset finishes the levelling (no 0x06/0x07/0x08); otherwise the stock card step follows.
     */
    private void startAutoCalibrationWithStoredZOffset(int soakSeconds) {
        mDisposables.add(getModel().getSlaveComputer()
                .startAutoCalibration(mGrid, mSoakBedTemperature, soakSeconds, true)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(reply -> {
                    finishHeatSoakTracking("auto reply");
                    if (mUserCancelled) {
                        Logger.i("Leveling: auto reply after cancel ignored");
                        return;
                    }
                    if (!reply.isOk()) {
                        Logger.w("Leveling: auto levelling failed, %s", reply);
                        mIsMovingSubject.onNext(false);
                        mAutoCalibrationResultSubject.onNext(false);
                        return;
                    }
                    if (mCurrentPoint != null) {
                        mCurrentPoint.setActivated(false);
                    }
                    if (reply.isOffsetApplied()) {
                        Logger.i("Leveling: mesh saved with the stored Z offset %s mm, no card step",
                                ZOffsetParams.formatMm(reply.offsetUm));
                        markAllPointsMeasured();
                        mUpdatePointsSubject.onNext(true);
                    } else {
                        Logger.i("Leveling: card step needed (%s)", reply);
                        mAutoZOffsetSubject.onNext(true);
                    }
                    mIsMovingSubject.onNext(false);
                    mMeshResultSubject.onNext(reply);
                }, e -> {
                    LogHelper.log(e);
                    finishHeatSoakTracking("auto error");
                    mIsMovingSubject.onNext(false);
                    mAutoCalibrationResultSubject.onNext(false);
                }));
    }

    private void markAllPointsMeasured() {
        if (mPoints == null) {
            return;
        }
        for (CalibrationPoint point : mPoints) {
            point.setSelected(true);
            point.setActivated(false);
        }
    }

    /**
     * Z-offset calibration (0x09 0x37): heat/soak as chosen, home, probe the bed centre. On success the
     * card step panel is shown (jog 0x06, Save 0x07 stores the offset, Back/Quit 0x08 discards).
     */
    private void startZOffsetCalibration() {
        mUserCancelled = false;
        mIsMovingSubject.onNext(true);

        final int soakSeconds = LevelingParams.soakMinutesToSeconds(mSoakMinutes);
        Logger.i("Leveling: start Z offset calibration, bed %d C, soak %d s", mSoakBedTemperature, soakSeconds);
        startHeatSoakTracking(soakSeconds);

        mDisposables.add(getModel().getSlaveComputer()
                .startZOffsetCalibration(mSoakBedTemperature, soakSeconds)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(err -> {
                    finishHeatSoakTracking("Z offset reply");
                    mIsMovingSubject.onNext(false);
                    if (mUserCancelled) {
                        Logger.i("Leveling: Z offset reply after cancel ignored");
                        return;
                    }
                    if (err == 0) {
                        Logger.i("Leveling: bed centre probed, card step");
                        mAutoZOffsetSubject.onNext(true);
                    } else {
                        Logger.w("Leveling: Z offset calibration failed, err %d", err);
                        mAutoCalibrationResultSubject.onNext(false);
                    }
                }, e -> {
                    LogHelper.log(e);
                    finishHeatSoakTracking("Z offset error");
                    mIsMovingSubject.onNext(false);
                    mAutoCalibrationResultSubject.onNext(false);
                }));
    }

    public void gotoPointManual(int order) {
        mIsMovingSubject.onNext(true);

        mDisposables.add(getModel().getSlaveComputer()
                .gotoCalibrationPoint(order)
                .subscribe(success -> {
                    Logger.d("Goto manual point %d", order);
                    mCalibratedPointCount++;
                    switchToPoint(order);
                    mUpdatePointsSubject.onNext(true);
                    mIsMovingSubject.onNext(false);
                }, e -> {
                    LogHelper.log(e);
                    mIsMovingSubject.onNext(false);
                }));
    }

    public void adjustCalibrationPoint(double offset) {
        mIsMovingSubject.onNext(true);

        mDisposables.add(getModel().getSlaveComputer()
                .moveCalibrationPoint(offset)
                .subscribe(success -> {
                    if (success) {
                        mIsAdjustZOffsetSubject.onNext(true);
                    }
                    mIsMovingSubject.onNext(false);
                }, e -> {
                    LogHelper.log(e);
                    mIsMovingSubject.onNext(false);
                }));
    }

    public Observable<Boolean> saveCalibration() {
        mIsMovingSubject.onNext(true);

        return getModel().getSlaveComputer()
                .saveCalibration()
                .doOnNext(success -> mIsMovingSubject.onNext(false))
                .doOnError(e -> mIsMovingSubject.onNext(false));
    }

    public Observable<Boolean> exitCalibration() {
        mIsMovingSubject.onNext(true);

        return getModel().getSlaveComputer()
                .exitCalibration()
                .doOnNext(success -> {
                    resetBehaviors();
                })
                .doOnError(e -> mIsMovingSubject.onNext(false));
    }

    public void resetBehaviors() {
        mIsMovingSubject.onNext(false);
        mAutoZOffsetSubject.onNext(false);
        mIsAdjustZOffsetSubject.onNext(false);
    }

    /**
     * Re-init fields and status ,clear disposables so we can start calibration again.
     *
     * @param fragmentSurvive If true, we need to re-init the fields, otherwise they'll be init in the
     *                        onCreate... method of the fragment.
     */
    public void clearCalibrationPointData(boolean fragmentSurvive) {
        if (fragmentSurvive && mPoints != null) {
            mCalibratedPointCount = 0;
            mCurrentDir = 0;
            for (CalibrationPoint point : mPoints) {
                point.setActivated(false);
                point.setSelected(false);
            }
        }
        // Clear the disposables, otherwise we may get multiple results though we only need one.
        mDisposables.clear();
    }

    public ArrayList<CalibrationPoint> initPoints() {
        if (mCalibratedPointCount != 0) {
            mCalibratedPointCount = 0;
        }
        final int[][] orders = generateViewOrders();
        mPoints = new ArrayList<>();
        for (int i = 1; i <= mGrid; i++) {
            for (int j = 1; j <= mGrid; j++) {
                int coordinateOrder = j + (mGrid * (mGrid - i));
                mPoints.add(new CalibrationPoint(i, j, orders[i - 1][j - 1], coordinateOrder));
            }
        }

        return mPoints;
    }

    private int[][] generateViewOrders() {
        int[][] orders = new int[mGrid][mGrid];
        int order = 1;
        int direction = 0;

        // init first element
        int currentRow = mGrid - 1;
        int currentColumn = 0;
        orders[currentRow][currentColumn] = order;
        order++;

        while (order <= mGrid * mGrid) {
            int nextRow = currentRow + mDirection[direction][0];
            int nextColumn = currentColumn + mDirection[direction][1];

            if (nextRow > mGrid - 1 || nextRow < 0 || nextColumn > mGrid - 1 || nextColumn < 0 || orders[nextRow][nextColumn] != 0) {
                // change direction
                direction = (direction + 1) % 4;
            } else {
                orders[nextRow][nextColumn] = order;
                currentRow = nextRow;
                currentColumn = nextColumn;
                order++;
            }
        }

        return orders;
    }

    private CalibrationPoint getPoint(int coordinateOrder) {
        if (coordinateOrder > mPoints.size() || coordinateOrder < 1) return null;

        for (CalibrationPoint point : mPoints) {
            if (point.getCoordinateOrder() == coordinateOrder)
                return point;
        }

        return null;
    }

    private CalibrationPoint getPoint(int row, int column) {
        if (row > mGrid || row < 1 || column > mGrid || column < 1) return null;

        int position = column + mGrid * (row - 1);
        if (position > mPoints.size() || position < 1) {
            return null;
        }

        return mPoints.get(position - 1);
    }

    private CalibrationPoint getNextPoint() {
        if (isAllPointSelected() || mCalibratedPointCount >= mPoints.size()) {
            // all points are selected, return current point
            Logger.d("All points are selected.");
            return mCurrentPoint;
        }

        // Check point if surrounded
        if (isCurrentPointSurrounded()) {
            // return unselected point
            for (CalibrationPoint point : mPoints) {
                if (!point.isSelected() && !point.isActivated()) {
                    return point;
                }
            }
        }

        // Find next point according to current direction.
        int nextRow = mCurrentPoint.getRow() + mDirection[mCurrentDir][0];
        int nextColumn = mCurrentPoint.getColumn() + mDirection[mCurrentDir][1];

        CalibrationPoint nextPoint = getPoint(nextRow, nextColumn);

        if (nextPoint == null) {
            // change direction
            mCurrentDir = (mCurrentDir + 1) % 4;
            Logger.d("Out of bound, mCurrentDir is %d", mCurrentDir);
            return getNextPoint();
        } else if (nextPoint.isSelected()) {
            // change direction
            mCurrentDir = (mCurrentDir + 1) % 4;
            Logger.d("NextPoint is Selected, mCurrentDir is %d", mCurrentDir);
            return getNextPoint();
        }

        return nextPoint;
    }

    private boolean isCurrentPointSurrounded() {
        boolean isSurrounded = true;
        for (int i = 0; i < 4; i++) {
            CalibrationPoint nextPoint = getPoint(mCurrentPoint.getRow() + mDirection[i][0],
                    mCurrentPoint.getColumn() + mDirection[i][1]);
            if (nextPoint == null) continue;
            if (!nextPoint.isSelected()) {
                isSurrounded = false;
            }
        }

        return isSurrounded;
    }

    // manual selected
    private void switchToPoint(int coordinateOrder) {
        if (mCurrentPoint.getViewOrder() != 0) {
            mCurrentPoint.setSelected(true);
            mCurrentPoint.setActivated(false);
        }

        mCurrentPoint = getPoint(coordinateOrder);
        if (mCurrentPoint == null) return;
        mCurrentPoint.setActivated(true);
        mCurrentPoint.setSelected(true);
    }

    // auto
    private void moveToNextPoint() {
        mCurrentPoint.setSelected(true);
        mCurrentPoint.setActivated(false);

        mCurrentPoint = getNextPoint();
        mCurrentPoint.setActivated(true);
    }

    public boolean isAllPointSelected() {
        if (mPoints == null || mPoints.isEmpty()) {
            // panel not set up yet (options dialog still open)
            return false;
        }
        for (CalibrationPoint point : mPoints) {
            if (!point.isSelected()) {
                return false;
            }
        }
        return true;
    }

    public void checkNextPointManual() {
        if (mCurrentPoint.getCoordinateOrder() == 0) {
            gotoPointManual(1);
        } else {
            CalibrationPoint nextPoint = getNextPoint();
            gotoPointManual(nextPoint.getCoordinateOrder());
        }
    }

    /**
     * Retry calibration: clear data and start again.
     */
    public void retryCalibration() {
        clearCalibrationPointData(true);
        resetBehaviors();
        startCalibration();
    }

    public void dispose() {
        mDisposables.dispose();
    }
}