package org.genecash.wifi_thermostat;

import android.app.TimePickerDialog;
import android.app.TimePickerDialog.OnTimeSetListener;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TimePicker;

public class TimeButton extends Button {
	int time;
	Context ctx;

	public TimeButton(Context context) {
		super(context);
		ctx = context;
		time = 0;
		setText();
		setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				// Create dialog to pick time & update button when clicked
				TimePickerDialog timepick = new TimePickerDialog(ctx, new OnTimeSetListener() {
					public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
						setHour(hourOfDay);
						setMinutes(minute);
						setText();
					}
				}, getHour(), getMinutes(), false);
				timepick.show();
			}
		});
	}

	public int getTime() {
		return time;
	}

	public void setTime(int time) {
		this.time = time;
	}

	public int getHour() {
		return time / 60;
	}

	public void setHour(int hour) {
		int t = time % 60;
		time = (hour * 60) + t;
	}

	public int getMinutes() {
		return time % 60;
	}

	public void setMinutes(int minutes) {
		int t = time / 60;
		time = minutes + (t * 60);
	}

	public void setText() {
		String meridian = "am";
		int min = time % 60;
		int hour = time / 60;
		if (hour > 11) {
			hour -= 12;
			meridian = "pm";
		}
		if (hour == 0) {
			hour = 12;
		}
		setText(String.format("%d:%02d %s", hour, min, meridian));
	}

}
