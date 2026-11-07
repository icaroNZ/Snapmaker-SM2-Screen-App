package com.snapmaker.fabscreen.modules.calibration;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.snapmaker.fabscreen.R;
import fabscreen.libraries.legacy.view.ActionButton;

import java.util.ArrayList;
import java.util.List;

import fabscreen.libraries.legacy.data.calibration.CalibrationPoint;

public class CalibrationPanelAdapter extends BaseAdapter {
    private Context mContext;
    private List<CalibrationPoint> mPoints;
    private boolean mIsButtonsEnabled;
    private OnPointClickListener mOnPointClickListener;
    private int mCellSizePx = 0; // 0 = layout default (40 dp)
    private boolean mShowIndex = true;

    public CalibrationPanelAdapter(Context context) {
        mContext = context;
    }

    @Override
    public int getCount() {
        // no points until the levelling options are chosen
        return mPoints == null ? 0 : mPoints.size();
    }

    /**
     * Cell size for grids above 7x7 (11 columns must fit in the 280 dp panel); index labels need about 36 dp.
     */
    public void setCellSize(int cellSizePx, boolean showIndex) {
        mCellSizePx = cellSizePx;
        mShowIndex = showIndex;
    }

    @Override
    public CalibrationPoint getItem(int position) {
        return mPoints.get(position);
    }

    public void setPoints(ArrayList<CalibrationPoint> points) {
        this.mPoints = points;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void setButtonsEnabled(boolean enabled) {
        mIsButtonsEnabled = enabled;
    }

    public void setOnPointClickListener(OnPointClickListener listener) {
        mOnPointClickListener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(mContext).inflate(R.layout.item_calibration_point, parent, false);
        }
        if (mCellSizePx > 0) {
            ViewGroup.LayoutParams lp = convertView.getLayoutParams();
            if (lp == null || lp.width != mCellSizePx || lp.height != mCellSizePx) {
                convertView.setLayoutParams(new AbsListView.LayoutParams(mCellSizePx, mCellSizePx));
            }
        }

        CalibrationPoint point = getItem(position);

        // TODO: refactor button view instead of ActionButton
        final ActionButton button = convertView.findViewById(R.id.btn_calibration_point);
        final TextView mTvIndex = convertView.findViewById(R.id.tv_calibration_index);

        mTvIndex.setText(String.valueOf(point.getViewOrder()));
        mTvIndex.setVisibility(mShowIndex ? View.VISIBLE : View.GONE);

        button.setEnabled(mIsButtonsEnabled);

        button.setActivated(point.isActivated());
        button.setSelected(point.isSelected());

        button.setOnClickListener(v -> mOnPointClickListener.onPointClick(point));

        return convertView;
    }

    public interface OnPointClickListener {
        void onPointClick(CalibrationPoint point);
    }
}
