package com.snapmaker.fabscreen.modules.calibration;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.snapmaker.fabscreen.R;
import fabscreen.libraries.legacy.base.BaseFragment;

import butterknife.BindView;
import butterknife.OnClick;
import io.reactivex.android.schedulers.AndroidSchedulers;

public class CalibrationIntroFragment extends BaseFragment {
    public static CalibrationIntroFragment newInstance() {
        return new CalibrationIntroFragment();
    }

    @BindView(R.id.tv_calibration_intro_title)
    TextView mTvIntroTitle;
    @BindView(R.id.tv_calibration_intro_content)
    TextView mTvIntroContent;

    @BindView(R.id.tv_calibration_zoffset_status)
    TextView mTvZOffsetStatus;
    @BindView(R.id.btn_calibration_intro_next)
    Button mBtnNext;
    @BindView(R.id.btn_calibration_zoffset)
    Button mBtnZOffset;

    private CalibrationViewModel mViewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = getViewModel();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initView();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_calibration_intro;
    }

    private void initView() {
        boolean isAutoMode = mViewModel.isAutoMode();
        setTitle(isAutoMode ? R.string.calibration_auto_leveling : R.string.calibration_manual_leveling);
        mTvIntroTitle.setText(R.string.calibration_intro_calibrate_the_bed);
        mTvIntroContent.setText(isAutoMode ? R.string.calibration_intro_auto_leveling_calibrate_the_bed_desc
                : R.string.calibration_intro_manual_leveling_calibrate_the_bed_desc);

        bindZOffsetEntries();
    }

    /**
     * Ask the controller once whether it has the separate Z-offset calibration (a250t.4+); the second entry
     * and the stored offset appear only when it answers.
     */
    private void bindZOffsetEntries() {
        mViewModel.getZOffsetSupportObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(support -> CalibrationEntries.render(support, mTvZOffsetStatus, mBtnNext, mBtnZOffset));
        mViewModel.checkZOffsetSupport();
    }

    @OnClick(R.id.btn_calibration_zoffset)
    void onClickZOffset() {
        mViewModel.selectZOffsetCalibration();
        if (getActivity() != null) {
            ((CalibrationActivity) getActivity()).startCalibrationFragment();
        }
    }

    @Override
    protected CalibrationViewModel getViewModel() {
        return getViewModelProvider().get(CalibrationViewModel.class);
    }

    @OnClick(R.id.btn_calibration_intro_next)
    void onClickNext() {
        mViewModel.selectMeshMeasurement();
        if (getActivity() != null) {
            ((CalibrationActivity) getActivity()).startCalibrationFragment();
        }
    }
}
