package com.snapmaker.fabscreen.modules.settings.advanced3dp;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.orhanobut.logger.Logger;
import com.snapmaker.fabscreen.R;
import fabscreen.libraries.legacy.base.BaseFragment;
import fabscreen.libraries.legacy.base.BaseViewModel;
import fabscreen.libraries.legacy.data.calibration.LevelingParams;

import java.util.Locale;

import butterknife.BindView;
import butterknife.OnClick;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.BehaviorSubject;

public class Settings3DPCalibrationGridFragment extends BaseFragment {
    public static Settings3DPCalibrationGridFragment getInstance() {
        return new Settings3DPCalibrationGridFragment();
    }

    @BindView(R.id.tv_set_calibration_grid_title)
    TextView mTvTitle;
    @BindView(R.id.tv_set_calibration_grid_value)
    TextView mTvValue;

    @BindView(R.id.btn_3dp_calibration_grid_minus)
    Button mBtnMinus;
    @BindView(R.id.btn_3dp_calibration_grid_plus)
    Button mBtnPlus;

    @BindView(R.id.iv_calibration_grid)
    ImageView mIvCalibrationGrid;

    private int mGrid = 3;

    private BehaviorSubject<Integer> mGridValueSubject = BehaviorSubject.createDefault(3);

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setTitle(R.string.settings_3dp_calibration_grid);

        initView();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_3dp_calibration_grid;
    }

    @Override
    protected BaseViewModel getViewModel() {
        return null;
    }

    private void initView() {
        // get initial value (3..11; the stock page only offered 3, 4 and 5)
        mGrid = LevelingParams.clampGrid(getModel().getPreferences().get3DPCalibrationGrid());
        mGridValueSubject.onNext(mGrid);

        mBtnMinus.setOnClickListener(v -> mGridValueSubject.onNext(LevelingParams.clampGrid(mGridValueSubject.getValue() - 1)));
        mBtnPlus.setOnClickListener(v -> mGridValueSubject.onNext(LevelingParams.clampGrid(mGridValueSubject.getValue() + 1)));

        mGridValueSubject
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(value -> {
                    mTvValue.setText(String.format(Locale.getDefault(), "%d × %d", value, value));
                    mBtnMinus.setEnabled(value > LevelingParams.GRID_MIN);
                    mBtnPlus.setEnabled(value < LevelingParams.GRID_MAX);
                    switch (value) {
                        case 3:
                            mIvCalibrationGrid.setImageResource(R.drawable.pic_3dp_calibration_9point_280x280);
                            break;
                        case 4:
                            mIvCalibrationGrid.setImageResource(R.drawable.pic_3dp_calibration_16point_280x280);
                            break;
                        case 5:
                            mIvCalibrationGrid.setImageResource(R.drawable.pic_3dp_calibration_25point_280x280);
                            break;
                        default:
                            // no picture for 6x6..11x11
                            mIvCalibrationGrid.setImageDrawable(null);
                            break;
                    }
                });
    }

    @OnClick(R.id.btn_3dp_calibration_grid_save)
    void onClickSave() {
        Logger.i("Settings: 3DP calibration grid %d", mGridValueSubject.getValue());
        getModel().getPreferences().set3DPCalibrationGrid(mGridValueSubject.getValue());
        back();
    }
}
