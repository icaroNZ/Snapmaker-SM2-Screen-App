package com.snapmaker.fabscreen.modules.calibration;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.snapmaker.fabscreen.R;

import fabscreen.libraries.legacy.data.calibration.ZOffsetParams;

/**
 * The two calibration entries on the intro pages ("Measure bed mesh" / "Calibrate Z offset") and the stored
 * Z offset line. Shown only when the controller (SM2-5.2.1-a250t.4+) answered the Z offset query; otherwise
 * the intro stays the stock page with its single Start button.
 */
final class CalibrationEntries {
    private CalibrationEntries() {
    }

    static void render(CalibrationViewModel.ZOffsetSupport support, TextView status, Button next, Button zOffset) {
        if (support == null || !support.isSupported()) {
            status.setVisibility(View.GONE);
            zOffset.setVisibility(View.GONE);
            next.setText(R.string.all_start);
            return;
        }

        final ZOffsetParams.StatusReply reply = support.reply;
        final String mm = ZOffsetParams.formatMm(reply.offsetUm);
        switch (reply.status) {
            case ZOffsetParams.STATUS_VALID:
                status.setText(status.getContext().getString(R.string.calibration_zoffset_status_valid, mm));
                break;
            case ZOffsetParams.STATUS_OUT_OF_RANGE:
                status.setText(status.getContext().getString(R.string.calibration_zoffset_status_out_of_range, mm));
                break;
            case ZOffsetParams.STATUS_OTHER_TOOLHEAD:
                status.setText(R.string.calibration_zoffset_status_other_toolhead);
                break;
            case ZOffsetParams.STATUS_NOT_SET:
            default:
                status.setText(R.string.calibration_zoffset_status_not_set);
                break;
        }
        status.setVisibility(View.VISIBLE);
        next.setText(R.string.calibration_entry_measure_mesh);
        zOffset.setVisibility(View.VISIBLE);
    }
}
