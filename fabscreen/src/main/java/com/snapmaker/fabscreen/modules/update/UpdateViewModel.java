package com.snapmaker.fabscreen.modules.update;

import android.content.Context;
import android.content.Intent;

import com.orhanobut.logger.Logger;
import com.snapmaker.fabscreen.FabScreenApplication;
import com.snapmaker.fabscreen.R;

import fabscreen.libraries.legacy.BaseApplication;
import fabscreen.libraries.legacy.base.BaseViewModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import fabscreen.libraries.legacy.data.Constants;
import fabscreen.libraries.legacy.data.Model;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacketContent;
import fabscreen.libraries.legacy.lib.LogHelper;
import fabscreen.libraries.legacy.lib.file.IFile;
import fabscreen.libraries.legacy.lib.file.IFileManager;
import fabscreen.libraries.legacy.lib.update.FabUpdatePackage;
import fabscreen.libraries.legacy.lib.update.FabUpdater;
import fabscreen.libraries.legacy.lib.update.UpdateSpace;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.SingleSubject;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

// TODO: 2021/8/24  viewModel must not acquire context.
public class UpdateViewModel extends BaseViewModel {
    private static final String TAG = "UpdateViewModel";

    private CompositeDisposable disposables = new CompositeDisposable();

    private Context mContext;
    private Model mModel;
    private FabUpdater mUpdater;

    private String mControllerVersion;
    private int mPackageIndex = -1;

    private IFile mFirmwareFile;

    private static final long MB = 1024 * 1024;
    private static final int CONTROLLER_VERSION_TIMEOUT_S = 3;

    private PublishSubject<Boolean> mUpdateCompleteSubject = PublishSubject.create();
    private BehaviorSubject<String> mMessageSubject = BehaviorSubject.createDefault("");
    private BehaviorSubject<Float> mProgressSubject = BehaviorSubject.createDefault(0f);
    private BehaviorSubject<String> mProgressTextSubject = BehaviorSubject.createDefault("");

    private Disposable mUpdateCheckSubscription;

    UpdateViewModel(Context context, Model model) {
        super();
        mContext = context;
        mModel = model;

        mUpdater = new FabUpdater();

        initEvents();
    }

    boolean parse(IFile file) {
        try {
            return mUpdater.parse(file);
        } catch (Exception e) {
            LogHelper.log(e);
            return false;
        }
    }

    public Observable<IFile> startSearchingFile(IFileManager fileManager, String filePath) {
        mMessageSubject.onNext(mContext.getResources().getString(R.string.settings_update_loading_update_file));
        return fileManager.search(filePath)
                .delay(300, Constants.TIME_UNIT)
                .doOnNext(file -> {
                    mFirmwareFile = file;
                });
    }

    Observable<Boolean> update() {
        Logger.i("Update: start, %d package(s)", mUpdater == null ? -1 : mUpdater.getPacketCount());
        getModel().getSlaveComputer().setHeartbeatEnabled(false);

        // SCR-16: the heartbeat (status polling) never came back after a failed update.
        Disposable done = mUpdateCompleteSubject.filter(success -> !success).take(1)
                .subscribe(failed -> {
                    getModel().getSlaveComputer().setHeartbeatEnabled(true);
                    Logger.i("Heartbeat re-enabled after update (success=false)");
                }, LogHelper::log);
        disposables.add(done);

        // SCR-16: the version request had no timeout and no error path, so a lost reply stalled the update.
        Disposable sub = getModel().getSlaveComputer().getControllerVersion()
                .timeout(CONTROLLER_VERSION_TIMEOUT_S, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(version -> {
                    mControllerVersion = version;
                    Logger.i("Update: controller reports version '%s'", version);

                    updatePackage(0);
                }, e -> {
                    Logger.e("Update: controller version request failed: %s", e);
                    mUpdateCompleteSubject.onNext(false);
                });
        disposables.add(sub);

        return mUpdateCompleteSubject;
    }

    private void initEvents() {
        Disposable sub = getModel().getSlaveComputer().watchPacketIndexRequest()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::sendPackagePart, e -> {
                    Logger.e("Update: package index watch failed: %s", e);
                    LogHelper.log(e);
                });
        disposables.add(sub);
    }

    private void updatePackage(int pid) {
        mPackageIndex = pid;

        if (pid == mUpdater.getPacketCount()) {
            // set update flag
            mModel.getPreferences().setMachineUpdatedFlag(true);
            mModel.getPreferences().setLastUpdatePackageVersion(mUpdater.getPackageVersion());

            if (mUpdater.getScreenUpdate() != null) {
                updateApp();
            } else {
                mUpdateCompleteSubject.onNext(true);
            }
        } else {
            byte[] p = mUpdater.usePacket(pid);

            if (p[0] == FabUpdatePackage.UpdatePackageHeader.TYPE_CONTROLLER_FIRMWARE) {
                mMessageSubject.onNext(mContext.getString(R.string.settings_update_msg_updating_controller));
            } else if (p[0] == FabUpdatePackage.UpdatePackageHeader.TYPE_MODULE_FIRMWARE) {
                mMessageSubject.onNext(mContext.getString(R.string.settings_update_msg_updating_modules));
            }

            // Update Controller if update version is not equals with current version.
            Logger.i("Update: package %d/%d type %d, bundle controller version '%s', controller '%s', force %b",
                    pid + 1, mUpdater.getPacketCount(), p[0], mUpdater.getControllerVersion(), mControllerVersion,
                    mUpdater.isForceUpdate());
            if (p[0] == FabUpdatePackage.UpdatePackageHeader.TYPE_CONTROLLER_FIRMWARE
                    && mUpdater.getControllerVersion().equals(mControllerVersion)
                    && !mUpdater.isForceUpdate()) {
                // skip the package
                Logger.i("Update: controller already at the bundle version, skipping package %d", pid + 1);
                updatePackage(pid + 1);
            } else {
                // Start sending update packet to Controller.
                startUpdatePackage();
            }
        }
    }

    private File saveToFile(byte[] packet) {
        // TODO: refactor File into IFile
        FileOutputStream fos;
        File file;
        try {
            file = new File(mModel.getCacheDir(), "update.apk");
            fos = new FileOutputStream(file);
            fos.write(packet, 0, packet.length);

            fos.flush();
            fos.close();
        } catch (IOException e) {
            LogHelper.log(e);
            file = null;
        }
        return file;
    }

    private void updateApp() {
        mMessageSubject.onNext(mContext.getString(R.string.settings_update_msg_updating_screen));
        File file = saveToFile(mUpdater.getScreenUpdate());
        if (file != null) {
            // Send broadcast to update FabScreen itself.
            Intent updateIntent = new Intent("com.snapmaker.updateApkBroadcast");
            updateIntent.putExtra("URL", file.getPath());
            updateIntent.putExtra("OPERATION", "local_file");
            mContext.sendBroadcast(updateIntent);
        }
    }

    private void startUpdatePackage() {
        mProgressTextSubject.onNext("");
        Disposable sub = getModel().getSlaveComputer().startUpdate()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(success -> {
                    if (success) {
                        // do nothing wait for request
                    } else {
                        mUpdateCompleteSubject.onNext(false);
                    }
                }, e -> {
                    LogHelper.log(e);
                    mUpdateCompleteSubject.onNext(false);
                });
        disposables.add(sub);
    }

    private void sendPackagePart(int index) {
        if (index < 0 || index > mUpdater.getPartCount()) {
            // SCR-16: an out-of-range request (e.g. a reply meant for another sub-command) used to
            // index past the parts array.
            Logger.e("Update: controller asked for part %d of %d, ignored", index, mUpdater.getPartCount());
            return;
        }
        if (index == mUpdater.getPartCount()) {
            Logger.i("Update: all %d parts sent", index);
            getModel().getSlaveComputer().sendUpdatePackage((byte) 0x02, (short) index, null);
            mProgressSubject.onNext(1f);
            checkPackageUpdated();
        } else {
            final byte[] content = mUpdater.getPart(index);
            getModel().getSlaveComputer().sendUpdatePackage((byte) 0x01, (short) index, content);
            mProgressSubject.onNext(1f * (index + 1) / mUpdater.getPartCount());
            mProgressTextSubject.onNext(String.format(Locale.US, "%d / %d", index + 1, mUpdater.getPartCount()));
        }
    }

    private void checkPackageUpdated() {
        mMessageSubject.onNext(mContext.getString(R.string.settings_update_msg_finishing_up_controller_update));
        // check if controller is available
        mUpdateCheckSubscription = Observable.interval(5, TimeUnit.SECONDS)
                .delaySubscription(10, TimeUnit.SECONDS)
                .flatMap(t ->
                        getModel().getSlaveComputer()
                                .requestMachineStatus()
                                .onErrorReturnItem(FabPacketContent.MachineStatus.getDefaultInstance())
                )
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(status -> {
                    if (!status.isDefault) {
                        mUpdateCheckSubscription.dispose();
                        mUpdateCheckSubscription = null;

                        updatePackage(mPackageIndex + 1);
                    }
                }, Throwable::printStackTrace);
    }

    public void setUpdateFile(IFile file) {
        mFirmwareFile = file;
    }

    public IFile getUpdateFile() {
        return mFirmwareFile;
    }

    public void saveUpdateFile(String folderPath, IFile file) {
        Logger.d("Start saving update file into cache…");
        File saveFolder = new File(folderPath);
        if (!saveFolder.exists()) {
            saveFolder.mkdir();
        }

        // clear last update files in folder
        File[] files = saveFolder.listFiles();
        if (files != null && files.length > 0) {
            for (File f : files) {
                f.delete();
            }
        }

        File saveFile = new File(saveFolder, "update.bin");
        BufferedSource bufferedSource = null;
        BufferedSink bufferedSink = null;
        IFileManager iFileManager = file.isLocal() ?
                FabScreenApplication.getInstance().getFabLocalFileManager() :
                FabScreenApplication.getInstance().getFabUsbFileManager();
        try {
            bufferedSource = Okio.buffer(Okio.source(iFileManager.getInputStream(file)));
            bufferedSink = Okio.buffer(Okio.sink(new FileOutputStream(saveFile)));
            // copy file from source with buffer
            int len;
            byte[] buffer = new byte[1024 * 16];
            while ((len = bufferedSource.read(buffer)) != -1) {
                bufferedSink.write(buffer, 0, len);
            }
            bufferedSink.close();
            bufferedSource.close();
        } catch (IOException e) {
            LogHelper.log(e);
        } finally {
            try {
                iFileManager.deviceHang();
                if (bufferedSink != null) {
                    bufferedSink.close();
                }
                if (bufferedSource != null) {
                    bufferedSource.close();
                }
            } catch (IOException e) {
                LogHelper.log(e);
            }
        }
    }

    /** Result of {@link #planSpaceForUpdate}: what is needed and which files would be removed. */
    public static final class SpacePlan {
        public final long requiredBytes;
        public final long availableBytes;
        public final List<IFile> filesToDelete;
        public final long bytesFreed;
        /** True when deleting every deletable file would still not be enough. */
        public final boolean impossible;

        SpacePlan(long requiredBytes, long availableBytes, List<IFile> filesToDelete, long bytesFreed, boolean impossible) {
            this.requiredBytes = requiredBytes;
            this.availableBytes = availableBytes;
            this.filesToDelete = filesToDelete;
            this.bytesFreed = bytesFreed;
            this.impossible = impossible;
        }

        public boolean isEnough() {
            return availableBytes >= requiredBytes;
        }
    }

    /**
     * SCR-11: work out whether the update fits and which oldest local files would have to go. It
     * never deletes anything: the caller asks the user first ({@link #deleteFiles}).
     */
    public Single<SpacePlan> planSpaceForUpdate(IFile targetFile) {
        SingleSubject<SpacePlan> resultSubject = SingleSubject.create();
        IFileManager fileManager = BaseApplication.getInstance().getFabLocalFileManager();
        fileManager.mount();
        long availableSpace = fileManager.getTotalSpace() - fileManager.getUsedSpace();
        long requireSpace = UpdateSpace.requiredBytes(targetFile.length(), targetFile.isLocal());
        long shortfall = UpdateSpace.shortfall(requireSpace, availableSpace);
        Logger.w("Update needs %d MB, free %d MB (bundle %d MB, local %b)", requireSpace / MB, availableSpace / MB,
                targetFile.length() / MB, targetFile.isLocal());

        if (shortfall == 0) {
            resultSubject.onSuccess(new SpacePlan(requireSpace, availableSpace, new ArrayList<>(), 0, false));
            return resultSubject.hide();
        }

        fileManager.listFiles()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(files -> {
                    ArrayList<IFile> candidates = new ArrayList<>();
                    for (IFile f : files) {
                        if (UpdateSpace.isDeletable(f.getName(), f.isDirectory())) {
                            candidates.add(f);
                        }
                    }
                    candidates.sort(Comparator.comparingLong(IFile::lastModified));
                    List<Long> sizes = new ArrayList<>();
                    for (IFile f : candidates) {
                        sizes.add(f.length());
                    }
                    int count = UpdateSpace.countOldestToFree(sizes, shortfall);
                    List<IFile> toDelete = new ArrayList<>(candidates.subList(0, count < 0 ? candidates.size() : count));
                    long freed = 0;
                    for (IFile f : toDelete) {
                        freed += f.length();
                    }
                    Logger.w("Update: short of %d MB; %d oldest file(s) (%d MB) would have to be deleted%s",
                            shortfall / MB, toDelete.size(), freed / MB, count < 0 ? ", still not enough" : "");
                    resultSubject.onSuccess(new SpacePlan(requireSpace, availableSpace, toDelete, freed, count < 0));
                }, e -> {
                    LogHelper.log(e);
                    resultSubject.onSuccess(new SpacePlan(requireSpace, availableSpace, new ArrayList<>(), 0, true));
                });
        return resultSubject.hide();
    }

    /** Delete files the user agreed to remove (SCR-11). */
    public void deleteFiles(List<IFile> files) {
        IFileManager fileManager = BaseApplication.getInstance().getFabLocalFileManager();
        for (IFile file : files) {
            Logger.w("Update: deleting %s (%d KB) with the user's consent", file.getName(), file.length() / 1024);
            try {
                fileManager.removeFile(file);
            } catch (IOException e) {
                Logger.e("Update: deleting %s failed: %s", file.getName(), e);
            }
        }
    }

    Observable<String> getUpdateMessageObservable() {
        return mMessageSubject;
    }

    Observable<String> getUpdateProgressTextObservable() {
        return mProgressTextSubject;
    }

    Observable<Float> getUpdateProgressObservable() {
        return mProgressSubject;
    }

    public void dispose() {
        if (mUpdateCheckSubscription != null && !mUpdateCheckSubscription.isDisposed()) {
            mUpdateCheckSubscription.dispose();
        }
        disposables.clear();
    }
}
