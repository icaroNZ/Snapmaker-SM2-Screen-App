package com.snapmaker.fabscreen.modules.calibration;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.orhanobut.logger.Logger;
import com.snapmaker.fabscreen.R;
import com.snapmaker.fabscreen.modules.home.HomeActivity;
import com.snapmaker.fabscreen.router.Router;

import java.util.ArrayList;

import butterknife.BindView;
import butterknife.OnClick;
import co.ceryle.segmentedbutton.SegmentedButtonGroup;
import fabscreen.libraries.legacy.base.BaseFragment;
import fabscreen.libraries.legacy.data.Constants;
import fabscreen.libraries.legacy.data.calibration.HeatSoakTracker;
import fabscreen.libraries.legacy.data.calibration.LevelingParams;
import fabscreen.libraries.legacy.data.calibration.CalibrationPoint;
import fabscreen.libraries.legacy.data.calibration.ZOffsetParams;
import fabscreen.libraries.legacy.lib.LogHelper;
import fabscreen.libraries.legacy.view.FabConfirm;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;

public class CalibrationFragment extends BaseFragment {
    public static CalibrationFragment newInstance() {
        return new CalibrationFragment();
    }

    @BindView(R.id.top_bar_back)
    Button mBtnBack;

    @BindView(R.id.gv_calibration)
    GridView mGvCalibrationPanel;

    @BindView(R.id.rl_calibration_panel_z_offset)
    View mViewAdjustZOffset;
    @BindView(R.id.tv_calibration_z_offset_heated_bed_warning)
    TextView mTvZOffsetHeatedBedWarning;
    @BindView(R.id.iv_calibration_panel_z_offset)
    ImageView mIvZOffsetCover;

    @BindView(R.id.sbg_calibration_steps)
    SegmentedButtonGroup mSbgSteps;

    @BindView(R.id.btn_calibration_move_up)
    Button mBtnMoveUp;
    @BindView(R.id.btn_calibration_move_down)
    Button mBtnMoveDown;
    @BindView(R.id.btn_calibration_next_point)
    Button mBtnNextPoint;

    @BindView(R.id.btn_calibration_save)
    Button mBtnSave;

    @BindView(R.id.tv_calibration_zoffset_probing)
    TextView mTvZOffsetProbing;

    @BindView(R.id.ll_calibration_heat_soak)
    View mViewHeatSoak;
    @BindView(R.id.tv_calibration_heat_soak_title)
    TextView mTvHeatSoakTitle;
    @BindView(R.id.tv_calibration_heat_soak_value)
    TextView mTvHeatSoakValue;
    @BindView(R.id.tv_calibration_heat_soak_desc)
    TextView mTvHeatSoakDesc;
    @BindView(R.id.btn_calibration_heat_soak_cancel)
    Button mBtnHeatSoakCancel;

    private CalibrationViewModel mViewModel;
    private CalibrationPanelAdapter mAdapter;

    private double mStep = 0.1;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mViewModel = getViewModel();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initView();

        showLevelingOptionsDialog();
    }

    /**
     * Ask grid, bed temperature and soak time, then set up the panel and start levelling.
     */
    private void showLevelingOptionsDialog() {
        // With the stock "heated leveling" switch on, the bed was pre-heated on the previous page: offer that value.
        int bedTemp = mViewModel.getSoakBedTemperature();
        if (mViewModel.isHeatedLevelingOn() && mViewModel.getHeatedLevelingTemperature() > 0) {
            bedTemp = Math.round(mViewModel.getHeatedLevelingTemperature());
        }
        // The stock app offers no grid choice on the A150 (grid stays 3 there). The Z-offset calibration
        // probes only the bed centre, so it asks only for the heat soak.
        final boolean zOffsetMode = mViewModel.isZOffsetMode();
        final boolean showGrid = !zOffsetMode
                && getModel().getMachineController().getMachineModel() != Constants.MACHINE_MODEL_SNAPMAKER_A150;

        LevelingOptionsDialog.create(getContext(), showGrid, mViewModel.getGridCount(), bedTemp, mViewModel.getSoakMinutes())
                .setOnStartListener((grid, temp, soakMinutes) -> {
                    mViewModel.setLevelingOptions(showGrid ? grid : mViewModel.getGridCount(), temp, soakMinutes);
                    if (zOffsetMode) {
                        updateZOffsetHeatedBedWarning(mViewModel.isHeatedLevelingOn() || mViewModel.isHeatSoakUsed());
                        Logger.i("Leveling: Z offset calibration panel");
                    } else {
                        setupCalibrationPanel();
                    }
                    mViewModel.startCalibration();
                    if (!mViewModel.isAutoMode() && (mViewModel.isHeatedLevelingOn() || mViewModel.isHeatSoakUsed())) {
                        showUpHeatedBedWarningDialog();
                    }
                })
                .setOnCancelListener(() -> {
                    // nothing started yet; only the stock pre-heat may have heated the bed
                    if (mViewModel.isHeatedLevelingOn()) {
                        mViewModel.turnOffBed();
                    }
                    requireActivity().finish();
                })
                .show();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_calibration;
    }

    @Override
    protected CalibrationViewModel getViewModel() {
        return getViewModelProvider().get(CalibrationViewModel.class);
    }

    @Override
    protected void back() {
        // Need to pop up confirm dialog if exiting calibration.
        FabConfirm.create(getContext())
                .setCanceledOnTouchOutSide(false)
                .setDescription(mViewModel.isZOffsetMode() ? R.string.calibration_exit_zoffset_confirm_desc
                        : mViewModel.isAutoMode() ? R.string.calibration_exit_auto_leveling_confirm_desc
                        : R.string.calibration_exit_manual_leveling_confirm_desc)
                .setConfirm(R.string.all_ok, (dialog, which) -> {
                    dialog.dismiss();

                    if (mViewModel.isHeatedLevelingOn() || mViewModel.isHeatSoakUsed()) {
                        mViewModel.turnOffBed();
                    }
                    quitCalibration();
                })
                .setCancel(R.string.all_cancel, (dialog, which) -> {
                    dialog.dismiss();
                }).show();
    }

    private void initView() {
        boolean needBackHome = requireActivity().getIntent().getBooleanExtra(Router.IntentKeys.NEED_BACK_HOME, false);
        mBtnSave.setText(needBackHome ? R.string.all_save : R.string.all_complete);
        boolean isAutoMode = mViewModel.isAutoMode();
        boolean isHeatedLeveling = mViewModel.isHeatedLevelingOn();
        if (mViewModel.isZOffsetMode()) {
            // only the bed centre is probed: no points panel
            mGvCalibrationPanel.setVisibility(View.INVISIBLE);
            mTvZOffsetProbing.setVisibility(View.VISIBLE);
        }
        if (isAutoMode) {
            setTitle(mViewModel.isZOffsetMode() ? R.string.calibration_zoffset_title : R.string.calibration_auto_leveling);
            mViewModel.getOffsetObservable()
                    .observeOn(AndroidSchedulers.mainThread())
                    .as(bindToLifecycle())
                    .subscribe(isOffset -> {
                        mViewAdjustZOffset.setVisibility(isOffset ? View.VISIBLE : View.GONE);
                        if (mViewModel.isZOffsetMode()) {
                            mTvZOffsetProbing.setVisibility(isOffset ? View.GONE : View.VISIBLE);
                        }
                    });

            // warning/picture are set again in setupCalibrationPanel() once the heat soak choice is known
            updateZOffsetHeatedBedWarning(isHeatedLeveling);
        } else {
            setTitle(R.string.calibration_manual_leveling);
            // the heated bed warning is shown after the levelling options dialog (showLevelingOptionsDialog)
        }
        mBtnNextPoint.setVisibility(isAutoMode ? View.GONE : View.VISIBLE);

        // points are added by setupCalibrationPanel() after the options dialog
        mAdapter = new CalibrationPanelAdapter(getContext());
        mAdapter.setOnPointClickListener(point -> {
            mViewModel.gotoPointManual(point.getCoordinateOrder());
        });

        bindButtons();

        bindHeatSoak();

        Observable
                .combineLatest(mViewModel.getUpdatePointsObservable(),
                        mViewModel.getIsMovingObservable(),
                        (updated, isMoving) -> true)
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(updated -> mAdapter.notifyDataSetChanged());

        mViewModel.getAutoCalibrationResultObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(isSuccess -> {
                    if (!isSuccess) {
                        showAutoCalibrationFailDialog();
                    }
                });

        mViewModel.getMeshResultObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(this::onMeshResult);
    }

    /**
     * Auto levelling reply when the stored Z offset was requested (controller a250t.4+).
     */
    private void onMeshResult(ZOffsetParams.StatusReply reply) {
        final String mm = ZOffsetParams.formatMm(reply.offsetUm);
        if (reply.isOffsetApplied()) {
            // finished in the controller (mesh saved, nozzle raised): no card step, no save/exit packets
            FabConfirm.create(getContext())
                    .setCanceledOnTouchOutSide(false)
                    .setDescription(getString(R.string.calibration_dialog_mesh_offset_applied, mm))
                    .setConfirm(R.string.all_ok, (dialog, which) -> {
                        dialog.dismiss();
                        finishAfterSave();
                    })
                    .show();
            return;
        }
        if (!reply.extended) {
            // older firmware answered the short form: stock card step, nothing to explain
            return;
        }
        final String desc;
        switch (reply.status) {
            case ZOffsetParams.STATUS_OUT_OF_RANGE:
                desc = getString(R.string.calibration_dialog_zoffset_out_of_range_card_step, mm);
                break;
            case ZOffsetParams.STATUS_OTHER_TOOLHEAD:
                desc = getString(R.string.calibration_dialog_zoffset_other_toolhead_card_step);
                break;
            case ZOffsetParams.STATUS_NOT_SET:
            default:
                desc = getString(R.string.calibration_dialog_zoffset_not_set_card_step);
                break;
        }
        FabConfirm.create(getContext())
                .setDescription(desc)
                .setConfirm(R.string.all_confirm, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void updateZOffsetHeatedBedWarning(boolean heated) {
        mTvZOffsetHeatedBedWarning.setVisibility(heated ? TextView.VISIBLE : TextView.GONE);
        mIvZOffsetCover.setBackgroundResource(heated ? R.drawable.pic_calibration_heated_leveling_adjust_zoffset_240x80
                : R.drawable.pic_calibration_adjust_zoffset_240x80);
    }

    /**
     * Points panel for the chosen grid (3x3..11x11). Up to 7x7 the stock 40 dp cells fit the 280 dp panel;
     * above that the cells shrink to 280/grid dp (25 dp at 11x11) and the index labels are hidden.
     */
    private void setupCalibrationPanel() {
        final int gridCount = mViewModel.getGridCount();
        ArrayList<CalibrationPoint> points = mViewModel.initPoints();
        mAdapter.setPoints(points);
        initCalibrationPanel(gridCount);

        if (mViewModel.isAutoMode()) {
            updateZOffsetHeatedBedWarning(mViewModel.isHeatedLevelingOn() || mViewModel.isHeatSoakUsed());
        }
        Logger.i("Leveling: panel %d x %d (%d points)", gridCount, gridCount, points.size());
    }

    /**
     * Show the heat soak progress (heating, soak countdown, waiting for measuring) with a Cancel button.
     * The Back button stays disabled while the controller is busy, so Cancel is the only way out.
     */
    private void bindHeatSoak() {
        mViewModel.getHeatSoakStatusObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(status -> {
                    mViewHeatSoak.setVisibility(status.isActive() ? View.VISIBLE : View.GONE);
                    if (!status.isActive()) {
                        return;
                    }
                    switch (status.phase) {
                        case HeatSoakTracker.PHASE_HEATING:
                            mTvHeatSoakTitle.setText(R.string.calibration_heat_soak_heating);
                            mTvHeatSoakValue.setText(getString(R.string.calibration_heat_soak_temp_value,
                                    status.bedTemperature, status.targetTemperature));
                            if (status.soakSeconds > 0) {
                                mTvHeatSoakDesc.setText(getString(R.string.calibration_heat_soak_heating_desc,
                                        LevelingParams.formatCountdown(status.soakSeconds)));
                            } else {
                                mTvHeatSoakDesc.setText(R.string.calibration_heat_soak_heating_no_soak_desc);
                            }
                            break;
                        case HeatSoakTracker.PHASE_SOAKING:
                            mTvHeatSoakTitle.setText(getString(R.string.calibration_heat_soak_soaking,
                                    status.targetTemperature));
                            mTvHeatSoakValue.setText(LevelingParams.formatCountdown(status.secondsLeft));
                            mTvHeatSoakDesc.setText(getString(R.string.calibration_heat_soak_soaking_desc,
                                    status.bedTemperature));
                            break;
                        case HeatSoakTracker.PHASE_WAITING:
                        default:
                            mTvHeatSoakTitle.setText(R.string.calibration_heat_soak_waiting);
                            mTvHeatSoakValue.setText(getString(R.string.calibration_heat_soak_temp_value,
                                    status.bedTemperature, status.targetTemperature));
                            mTvHeatSoakDesc.setText(R.string.calibration_heat_soak_waiting_desc);
                            break;
                    }
                });

        mBtnHeatSoakCancel.setOnClickListener(v -> FabConfirm.create(getContext())
                .setCanceledOnTouchOutSide(false)
                .setDescription(R.string.calibration_heat_soak_cancel_confirm_desc)
                .setConfirm(R.string.all_ok, (dialog, which) -> {
                    dialog.dismiss();
                    mBtnHeatSoakCancel.setEnabled(false);
                    // drop the pending request first, then exit levelling aborts the soak in the controller
                    // (the controller turns the bed off only when it is still heating/soaking)
                    mViewModel.cancelLeveling();
                    // If the soak already ended (controller homing/probing), the controller keeps the bed at
                    // temperature, so turn it off here too (harmless if already off).
                    mViewModel.turnOffBed();
                    quitCalibration();
                })
                .setCancel(R.string.all_cancel, (dialog, which) -> dialog.dismiss())
                .show());
    }

    private void showAutoCalibrationFailDialog() {
        if (mViewModel.isUserCancelled()) {
            return;
        }
        final int desc;
        if (mViewModel.isZOffsetMode()) {
            desc = R.string.calibration_dialog_zoffset_fail_desc;
        } else if (!mViewModel.isAutoMode()) {
            desc = R.string.calibration_dialog_leveling_start_fail_desc;
        } else if (mViewModel.isHeatSoakUsed()) {
            desc = R.string.calibration_dialog_heat_soak_leveling_fail_desc;
        } else {
            desc = R.string.calibration_dialog_auto_calibration_fail_desc;
        }
        FabConfirm.create(getContext())
                .setCanceledOnTouchOutSide(false)
                .setIcon(R.drawable.pic_dialog_warning_72x72)
                .setDescription(desc)
                .setConfirm(R.string.all_retry, (dialog, which) -> {
                    dialog.dismiss();
                    //retry
                    mViewModel.retryCalibration();

                })
                .setCancel(R.string.all_quit, (dialog, which) -> {
                    dialog.dismiss();
                    //quit
                    if (mViewModel.isHeatedLevelingOn() || mViewModel.isHeatSoakUsed()) {
                        mViewModel.turnOffBed();
                    }
                    quitCalibration();
                })
                .show();
    }

    private void quitCalibration() {
        mViewModel.exitCalibration()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(success -> requireActivity().finish(), LogHelper::log);
    }

    private void initCalibrationPanel(int gridCount) {
        // 40 dp cells (stock) up to 7x7, smaller cells above so that 11 columns still fit in 280 dp.
        final int cellDp = Math.min(40, 280 / gridCount);
        mAdapter.setCellSize(dp2px(cellDp), cellDp >= 36);
        mGvCalibrationPanel.setColumnWidth(dp2px(cellDp));
        mGvCalibrationPanel.setNumColumns(gridCount);
        mGvCalibrationPanel.setAdapter(mAdapter);

        // Use gridCount to calculate spacing.
        final int spacing = Math.max(0, (280 - (cellDp * gridCount)) / (gridCount - 1));
        mGvCalibrationPanel.setVerticalSpacing(dp2px(spacing));
        mGvCalibrationPanel.setHorizontalSpacing(dp2px(spacing));
        switch (gridCount) {
            case 4:
                mGvCalibrationPanel.setBackgroundResource(R.drawable.pic_3dp_calibration_16point_280x280);
                break;
            case 5:
                mGvCalibrationPanel.setBackgroundResource(R.drawable.pic_3dp_calibration_25point_280x280);
                break;
            case 3:
                mGvCalibrationPanel.setBackgroundResource(R.drawable.pic_3dp_calibration_9point_280x280);
                break;
            default:
                // no picture for 6x6..11x11
                mGvCalibrationPanel.setBackground(null);
                break;
        }
    }

    private int dp2px(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void bindButtons() {
        // Step buttons
        mSbgSteps.setOnClickedButtonPosition(position -> {
            switch (position) {
                case 0:
                    mStep = 0.05;
                    break;
                case 1:
                    mStep = 0.1;
                    break;
                case 2:
                    mStep = 0.5;
                    break;
            }
        });

        // bind isMoving Event
        mViewModel.getIsMovingObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(isMoving -> {
                    mBtnBack.setEnabled(!isMoving);
                    mSbgSteps.setEnabled(!isMoving);
                    mBtnMoveUp.setEnabled(!isMoving);
                    mBtnMoveDown.setEnabled(!isMoving);
                    mBtnNextPoint.setEnabled(!isMoving && !mViewModel.isAllPointSelected());
                    mBtnSave.setEnabled(!isMoving && (mViewModel.isZOffsetMode() || mViewModel.isAllPointSelected())
                            && mViewModel.isAdjustZOffset());
                    mAdapter.setButtonsEnabled(!isMoving && !mViewModel.isAutoMode());
                });
    }

    private void showUpHeatedBedWarningDialog() {
        FabConfirm.create(getContext())
                .setDescription(R.string.calibration_dialog_z_offset_heated_bed_warning_desc)
                .setConfirm(R.string.all_confirm, ((dialog, which) -> {
                    dialog.dismiss();
                }))
                .show();
    }

    @OnClick(R.id.btn_calibration_move_up)
    void onClickUp() {
        mViewModel.adjustCalibrationPoint(mStep);
    }

    @OnClick(R.id.btn_calibration_move_down)
    void onClickDown() {
        mViewModel.adjustCalibrationPoint(-mStep);
    }

    @OnClick(R.id.btn_calibration_next_point)
    void onClickNext() {
        mViewModel.checkNextPointManual();
    }

    @OnClick(R.id.btn_calibration_save)
    void onClickSave() {
        if (mViewModel.isZOffsetMode()) {
            saveZOffset();
            return;
        }
        mViewModel.saveCalibration()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(s -> {
                    Logger.d("3DP Calibration Saved.");
                    finishAfterSave();
                }, LogHelper::log);
    }

    /**
     * Z-offset calibration Save (0x09 0x07): the controller stores the offset (err 0) or refuses (err 1,
     * then the levelling session is over: retry runs the centre probe again).
     */
    private void saveZOffset() {
        mViewModel.saveCalibration()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(stored -> {
                    Logger.i("Leveling: Z offset save %s", stored ? "stored" : "refused");
                    if (!stored) {
                        showZOffsetNotStoredDialog();
                        return;
                    }
                    mViewModel.queryZOffset()
                            .observeOn(AndroidSchedulers.mainThread())
                            .as(bindToLifecycle())
                            .subscribe(reply -> {
                                Logger.i("Leveling: Z offset after save %s", reply);
                                showZOffsetSavedDialog(getString(R.string.calibration_dialog_zoffset_saved,
                                        ZOffsetParams.formatMm(reply.offsetUm)));
                            }, e -> {
                                Logger.w("Leveling: Z offset query after save failed (%s)", e.toString());
                                showZOffsetSavedDialog(getString(R.string.calibration_dialog_zoffset_saved_no_value));
                            });
                }, LogHelper::log);
    }

    private void showZOffsetSavedDialog(String desc) {
        FabConfirm.create(getContext())
                .setCanceledOnTouchOutSide(false)
                .setDescription(desc)
                .setConfirm(R.string.all_ok, (dialog, which) -> {
                    dialog.dismiss();
                    finishAfterSave();
                })
                .show();
    }

    private void showZOffsetNotStoredDialog() {
        FabConfirm.create(getContext())
                .setCanceledOnTouchOutSide(false)
                .setIcon(R.drawable.pic_dialog_warning_72x72)
                .setDescription(R.string.calibration_dialog_zoffset_not_stored)
                .setConfirm(R.string.all_retry, (dialog, which) -> {
                    dialog.dismiss();
                    mViewModel.retryCalibration();
                })
                .setCancel(R.string.all_quit, (dialog, which) -> {
                    dialog.dismiss();
                    if (mViewModel.isHeatedLevelingOn() || mViewModel.isHeatSoakUsed()) {
                        mViewModel.turnOffBed();
                    }
                    quitCalibration();
                })
                .show();
    }

    /**
     * After a successful save (or a mesh finished with the stored Z offset): remember the stock pre-heat
     * temperature, turn the bed off if it was heated, then go home or back to the caller.
     */
    private void finishAfterSave() {
        if (mViewModel.isHeatedLevelingOn()) {
            // Set temperature into preferences.
            final float heatedLevelingTemp = mViewModel.getHeatedLevelingTemperature();
            getModel().getPreferences().set3DPCalibrationHeatedUpTemperature(heatedLevelingTemp);
        }
        if (mViewModel.isHeatedLevelingOn() || mViewModel.isHeatSoakUsed()) {
            // Turn off heated bed for safe when calibration was done already
            // (the controller keeps the soak temperature after levelling).
            Logger.i("Leveling: saved, turning the bed off");
            mViewModel.turnOffBed();
        }

        Intent intent = requireActivity().getIntent();
        if (intent != null && intent.getBooleanExtra(Router.IntentKeys.NEED_BACK_HOME, false)) {
            backToHome(HomeActivity.class);
        } else {
            finishWithResultOk();
        }
    }
}
