package com.snapmaker.fabscreen.modules.calibration;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.orhanobut.logger.Logger;
import com.snapmaker.fabscreen.R;

import fabscreen.libraries.legacy.data.calibration.LevelingParams;

/**
 * Asked before bed levelling starts: measurement grid (3x3..11x11), bed temperature (0 = no heating,
 * up to {@link LevelingParams#BED_TEMP_MAX}) and soak time (0..60 min). The controller (firmware
 * SM2-5.2.1-a250t.3 or later) heats the bed, waits until it is at temperature, soaks, then measures.
 */
public class LevelingOptionsDialog {

    public interface OnStartListener {
        void onStart(int grid, int bedTemperature, int soakMinutes);
    }

    private final AlertDialog mDialog;
    private final Context mContext;

    private final TextView mTvGrid;
    private final TextView mTvTemp;
    private final TextView mTvSoak;
    private final TextView mTvSoakLabel;
    private final Button mBtnGridMinus;
    private final Button mBtnGridPlus;
    private final Button mBtnTempMinus;
    private final Button mBtnTempPlus;
    private final Button mBtnSoakMinus;
    private final Button mBtnSoakPlus;

    private int mGrid;
    private int mBedTemp;
    private int mSoakMinutes;

    private OnStartListener mOnStartListener;
    private Runnable mOnCancelListener;

    /**
     * @param showGrid false keeps the grid unchanged and hides its row (A150: the stock app offers no grid there)
     */
    public static LevelingOptionsDialog create(Context context, boolean showGrid, int grid, int bedTemp, int soakMinutes) {
        return new LevelingOptionsDialog(context, showGrid, grid, bedTemp, soakMinutes);
    }

    private LevelingOptionsDialog(Context context, boolean showGrid, int grid, int bedTemp, int soakMinutes) {
        mContext = context;
        mGrid = LevelingParams.clampGrid(grid);
        mBedTemp = LevelingParams.clampBedTemperature(bedTemp);
        mSoakMinutes = LevelingParams.clampSoakMinutes(soakMinutes);

        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppTheme_Dialog);
        mDialog = builder.create();
        mDialog.setCancelable(false);
        mDialog.setCanceledOnTouchOutside(false);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_calibration_leveling_options, null);
        mDialog.setView(view);

        view.findViewById(R.id.ll_leveling_options_grid).setVisibility(showGrid ? View.VISIBLE : View.GONE);

        mTvGrid = view.findViewById(R.id.tv_leveling_options_grid_value);
        mTvTemp = view.findViewById(R.id.tv_leveling_options_temp_value);
        mTvSoak = view.findViewById(R.id.tv_leveling_options_soak_value);
        mTvSoakLabel = view.findViewById(R.id.tv_leveling_options_soak_label);
        mBtnGridMinus = view.findViewById(R.id.btn_leveling_options_grid_minus);
        mBtnGridPlus = view.findViewById(R.id.btn_leveling_options_grid_plus);
        mBtnTempMinus = view.findViewById(R.id.btn_leveling_options_temp_minus);
        mBtnTempPlus = view.findViewById(R.id.btn_leveling_options_temp_plus);
        mBtnSoakMinus = view.findViewById(R.id.btn_leveling_options_soak_minus);
        mBtnSoakPlus = view.findViewById(R.id.btn_leveling_options_soak_plus);

        mBtnGridMinus.setOnClickListener(v -> {
            mGrid = LevelingParams.clampGrid(mGrid - 1);
            refresh();
        });
        mBtnGridPlus.setOnClickListener(v -> {
            mGrid = LevelingParams.clampGrid(mGrid + 1);
            refresh();
        });
        mBtnTempMinus.setOnClickListener(v -> {
            mBedTemp = LevelingParams.stepBedTemperature(mBedTemp, false);
            refresh();
        });
        mBtnTempPlus.setOnClickListener(v -> {
            mBedTemp = LevelingParams.stepBedTemperature(mBedTemp, true);
            refresh();
        });
        mBtnSoakMinus.setOnClickListener(v -> {
            mSoakMinutes = LevelingParams.stepSoakMinutes(mSoakMinutes, false);
            refresh();
        });
        mBtnSoakPlus.setOnClickListener(v -> {
            mSoakMinutes = LevelingParams.stepSoakMinutes(mSoakMinutes, true);
            refresh();
        });

        view.findViewById(R.id.btn_leveling_options_cancel).setOnClickListener(v -> {
            Logger.i("Leveling: options dialog cancelled");
            mDialog.dismiss();
            if (mOnCancelListener != null) {
                mOnCancelListener.run();
            }
        });
        view.findViewById(R.id.btn_leveling_options_start).setOnClickListener(v -> {
            Logger.i("Leveling: options dialog start, grid %d, bed %d C, soak %d min", mGrid, mBedTemp, mSoakMinutes);
            mDialog.dismiss();
            if (mOnStartListener != null) {
                mOnStartListener.onStart(mGrid, mBedTemp, mSoakMinutes);
            }
        });

        refresh();
    }

    private void refresh() {
        mTvGrid.setText(mContext.getString(R.string.calibration_leveling_options_grid_value, mGrid));
        mBtnGridMinus.setEnabled(mGrid > LevelingParams.GRID_MIN);
        mBtnGridPlus.setEnabled(mGrid < LevelingParams.GRID_MAX);

        if (mBedTemp == 0) {
            mTvTemp.setText(R.string.calibration_leveling_options_bed_off);
        } else {
            mTvTemp.setText(mContext.getString(R.string.calibration_leveling_options_temp_value, mBedTemp));
        }
        mBtnTempMinus.setEnabled(mBedTemp > 0);
        mBtnTempPlus.setEnabled(mBedTemp < LevelingParams.BED_TEMP_MAX);

        // the soak only applies when the bed is heated (the controller ignores it otherwise)
        final boolean heating = mBedTemp > 0;
        mTvSoak.setText(mContext.getString(R.string.calibration_leveling_options_soak_value, mSoakMinutes));
        mTvSoak.setAlpha(heating ? 1f : 0.4f);
        mTvSoakLabel.setAlpha(heating ? 1f : 0.4f);
        mBtnSoakMinus.setEnabled(heating && mSoakMinutes > 0);
        mBtnSoakPlus.setEnabled(heating && mSoakMinutes < LevelingParams.SOAK_MINUTES_MAX);
    }

    public LevelingOptionsDialog setOnStartListener(OnStartListener listener) {
        mOnStartListener = listener;
        return this;
    }

    public LevelingOptionsDialog setOnCancelListener(Runnable listener) {
        mOnCancelListener = listener;
        return this;
    }

    public void show() {
        mDialog.show();
        // same trick as FabConfirm: fix the width after show()
        if (mDialog.getWindow() != null) {
            mDialog.getWindow().setBackgroundDrawableResource(R.color.default_theme_dark_mask);
            mDialog.getWindow().setLayout(300 * 2, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    public void dismiss() {
        mDialog.dismiss();
    }

    public boolean isShowing() {
        return mDialog.isShowing();
    }
}
