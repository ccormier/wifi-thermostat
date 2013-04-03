package org.genecash.wifi_thermostat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.genecash.thermostat.R;
import org.json.JSONArray;
import org.json.JSONObject;

import android.app.ActionBar;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.ToggleButton;

public class Thermostat extends Activity {
	// persistent preferences values
	private static SharedPreferences sSettings;
	private static final String PREFS_NAME = "SpendItPrefs";
	protected static final String PREF_TAB = "Tab";

	// menu constants
	private static final int MENU_DELETE = 0;
	private static final int MENU_ADD_BEFORE = 1;
	private static final int MENU_ADD_AFTER = 2;
	private static final int MENU_COPY_ABOVE = 3;
	private static final int MENU_COPY_BELOW = 4;
	private static final int MENU_REFRESH = 5;

	// bundle keys
	private static final String ADDR_KEY = "addr";

	// controls
	int oldMode;
	int oldFan;
	boolean oldHold;
	double oldTarget;
	double currentTarget;
	String addr = null;
	String targetKey;
	Spinner mode;
	Spinner status_fan;
	TextView status_time;
	TextView status_temp;
	TextView status_target;
	TextView status_override;
	TextView msg_line;
	Button status_time_set;
	ImageButton status_incr;
	ImageButton status_decr;
	Button status_set;
	Button status_refresh;
	ToggleButton status_hold;
	ImageView status_fan_icon;
	ActionBar actionBar = null;

	/**
	 * The thermostat is single-threaded, this ensures we stick to that
	 */
	final ReentrantLock netLock = new ReentrantLock();

	// old and new control states
	Bundle state_old;
	final HashMap<String, JSONObject> state_new = new HashMap<String, JSONObject>();

	SimpleDateFormat prettyFormat = new SimpleDateFormat("hh:mm a", Locale.US);
	Calendar cal = prettyFormat.getCalendar();
	// no clue how to get these in a localized manner, so I hardcoded them
	String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		setContentView(R.layout.thermostat);

		sSettings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
		state_old = savedInstanceState;

		// find controls
		mode = (Spinner) findViewById(R.id.mode);
		msg_line = (TextView) findViewById(R.id.status);
		status_time = (TextView) findViewById(R.id.status_time);
		status_temp = (TextView) findViewById(R.id.status_temp);
		status_target = (TextView) findViewById(R.id.status_target);
		status_override = (TextView) findViewById(R.id.status_override);
		status_incr = (ImageButton) findViewById(R.id.status_incr);
		status_decr = (ImageButton) findViewById(R.id.status_decr);
		status_set = (Button) findViewById(R.id.status_set);
		status_time_set = (Button) findViewById(R.id.status_time_set);
		status_refresh = (Button) findViewById(R.id.status_refresh);
		status_hold = (ToggleButton) findViewById(R.id.status_hold);
		status_fan_icon = (ImageView) findViewById(R.id.status_fan_icon);
		status_fan = (Spinner) findViewById(R.id.status_fan);

		// locate thermostat on the network
		if ((state_old != null) && (state_old.containsKey(ADDR_KEY))) {
			addr = state_old.getString(ADDR_KEY);
		} else {
			AsyncTask<Void, Void, String> task = new Discover().execute();
			try {
				// wait for it to finish and fetch the result
				addr = task.get();
			} catch (Exception e) {
				addr = "*Thermostat not found\n\n" + e;
			}
			if (addr.startsWith("*")) {
				// we got an error looking for the thermostat, display it and bail
				status(addr.substring(1));
				addr = null;
				return;
			}
		}

		// set up action bar for tabs
		actionBar = getActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

		// create 1st tab
		Tab tab = actionBar.newTab();
		tab.setText("Status");
		tab.setTabListener(new StatusTabListener<Fragment>(R.id.layout_status));
		actionBar.addTab(tab);

		// create 2nd tab
		tab = actionBar.newTab();
		tab.setText("Cooling");
		tab.setTabListener(new ProgramTabListener<Fragment>(R.id.cool_layout, R.id.cool_update, R.id.cool_table, "tstat/program/cool",
				"Loading cooling program"));
		actionBar.addTab(tab);

		// reselect the previously selected tab
		if (tab.getText().toString().equals(sSettings.getString(PREF_TAB, ""))) {
			tab.select();
		}

		// create 3rd tab
		tab = actionBar.newTab();
		tab.setText("Heating");
		tab.setTabListener(new ProgramTabListener<Fragment>(R.id.heat_layout, R.id.heat_update, R.id.heat_table, "tstat/program/heat",
				"Loading heating program"));
		actionBar.addTab(tab);

		// reselect the previously selected tab
		if (tab.getText().toString().equals(sSettings.getString(PREF_TAB, ""))) {
			tab.select();
		}

		// update mode when it changes
		oldMode = -1;
		mode.setOnItemSelectedListener(new OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> arg0, View v, int position, long id) {
				// if the setting hasn't actually changed, we need to ignore it
				// the order of these "if" statements is important
				if (oldMode == -1) {
					oldMode = position;
				}
				if (position == oldMode) {
					return;
				}
				oldMode = position;
				JSONObject json = new JSONObject();
				try {
					json.put("tmode", mode.getSelectedItemPosition());
				} catch (Exception e) {
					status(e);
					return;
				}
				new WriteURL().execute("tstat", json.toString(), "Setting mode");
				new FetchStatus().execute("tstat", "Loading status");
			}

			@Override
			public void onNothingSelected(AdapterView<?> arg0) {
			}
		});

		// button to sync thermostat time to phone
		status_time_set.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				JSONObject json = new JSONObject();
				JSONObject time = new JSONObject();
				try {
					time.put("day", cal.get(Calendar.DAY_OF_WEEK));
					time.put("hour", cal.get(Calendar.HOUR_OF_DAY));
					time.put("minute", cal.get(Calendar.MINUTE));
					json.put("time", time);
				} catch (Exception e) {
					status(e);
					return;
				}
				new WriteURL().execute("tstat", json.toString(), "Setting time");
				new FetchStatus().execute("tstat", "Loading status");
			}
		});

		// button to increment target temperature
		status_incr.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				changeTarget(1);
			}
		});

		// button to decrement target temperature
		status_decr.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				changeTarget(-1);
			}
		});

		// button to set new temperature target
		status_set.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				JSONObject json = new JSONObject();
				try {
					json.put(targetKey, currentTarget);
				} catch (Exception e) {
					status(e);
					return;
				}
				new WriteURL().execute("tstat", json.toString(), "Setting new temperature target");
				new FetchStatus().execute("tstat", "Loading status");
			}
		});

		// update hold when it toggles
		oldHold = false;
		status_hold.setOnCheckedChangeListener(new OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				if (oldHold != isChecked) {
					JSONObject json = new JSONObject();
					try {
						json.put("hold", isChecked ? 1 : 0);
					} catch (Exception e) {
						status(e);
						return;
					}
					new WriteURL().execute("tstat", json.toString(), "Setting hold mode");
					new FetchStatus().execute("tstat", "Loading status");
				}
			}
		});

		// update fan when it changes
		oldFan = -1;
		status_fan.setOnItemSelectedListener(new OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> arg0, View v, int position, long id) {
				// if the setting hasn't actually changed, we need to ignore it
				// the order of these "if" statements is important
				if (oldFan == -1) {
					oldFan = position;
				}
				if (position == oldFan) {
					return;
				}
				oldFan = position;
				JSONObject json = new JSONObject();
				try {
					json.put("fmode", status_fan.getSelectedItemPosition());
				} catch (Exception e) {
					status(e);
					return;
				}
				new WriteURL().execute("tstat", json.toString(), "Setting fan");
				new FetchStatus().execute("tstat", "Loading status");
			}

			@Override
			public void onNothingSelected(AdapterView<?> arg0) {
			}
		});

		// button to refresh status tab
		status_refresh.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				new FetchStatus().execute("tstat", "Loading status");
			}
		});

		// initial load of mode spinner & status page
		new FetchStatus().execute("tstat", "Loading status");
	}

	// squirrel away our hard-earned data when bad things happen
	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		// save thermostat address
		if (addr != null) {
			outState.putString(ADDR_KEY, addr);
		}
		// save cooling/heating programs
		for (String s : state_new.keySet()) {
			outState.putString(s, state_new.get(s).toString());
		}
		// save currently selected tab as a preference so it stays around
		if (actionBar != null) {
			SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, 0).edit();
			editor.putString(PREF_TAB, actionBar.getSelectedTab().getText().toString());
			editor.commit();
		}
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		menu.clear();

		// no thermostat, so punt
		if (addr == null) {
			return true;
		}

		// these only make sense if we have a focused control
		View v = this.getCurrentFocus();
		if ((v != null) && (v instanceof EditText)) {
			// figure out which row this is
			TableRow row = (TableRow) v.getParent();
			if (row.getChildCount() > 3) {
				// should not be able to delete only control
				menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "Delete item");
			}
			menu.add(Menu.NONE, MENU_ADD_BEFORE, Menu.NONE, "Insert item before");
			menu.add(Menu.NONE, MENU_ADD_AFTER, Menu.NONE, "Insert item after");

			// figure out where we are in the row
			TableLayout tbl = (TableLayout) row.getParent();
			int i = tbl.indexOfChild(row);
			if (i > 0) {
				// not the first row
				menu.add(Menu.NONE, MENU_COPY_ABOVE, Menu.NONE, "Copy row above");
			}
			if (i < 6) {
				// not the last row
				menu.add(Menu.NONE, MENU_COPY_BELOW, Menu.NONE, "Copy row below");
			}
		}
		menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, "Refresh programs");
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int choice = item.getItemId();

		// delete old controls and refetch data
		if (choice == MENU_REFRESH) {
			// delete old controls
			TableLayout tbl;
			tbl = (TableLayout) findViewById(R.id.cool_table);
			tbl.removeViews(0, tbl.getChildCount());
			tbl = (TableLayout) findViewById(R.id.heat_table);
			tbl.removeViews(0, tbl.getChildCount());
			state_old = null;

			// reload tab
			actionBar.selectTab(actionBar.getSelectedTab());
			return true;
		}

		// do something with/to a focused control
		View v = this.getCurrentFocus();
		if (v == null) {
			return true;
		}
		if (!(v instanceof EditText)) {
			return true;
		}

		// figure out where we are in the row
		TableRow row = (TableRow) v.getParent();
		int i = row.indexOfChild(v);

		// delete current temperature control
		if (choice == MENU_DELETE) {
			row.removeView(v);
			row.removeViewAt(i - 1);

			// fix focus
			if (i > row.getChildCount()) {
				i = row.getChildCount() - 1;
			}
			row.getChildAt(i).requestFocus();
			return true;
		}

		// add control before or after this one
		if ((choice == MENU_ADD_BEFORE) || (choice == MENU_ADD_AFTER)) {
			if (choice == MENU_ADD_BEFORE) {
				i--;
			} else {
				i++;
			}
			row.addView(createTemp(v.getMeasuredWidth()), i);
			row.addView(new TimeButton(this), i);
			return true;
		}

		// replace this row with a copy of the row above or below
		if ((choice == MENU_COPY_ABOVE) || (choice == MENU_COPY_BELOW)) {
			// find our current row
			TableLayout tbl = (TableLayout) row.getParent();
			int j = tbl.indexOfChild(row);

			// remove current controls
			row.removeViews(1, row.getChildCount() - 1);

			// find source sibling row
			TableRow src;
			if (choice == MENU_COPY_ABOVE) {
				src = (TableRow) tbl.getChildAt(j - 1);
			} else {
				src = (TableRow) tbl.getChildAt(j + 1);
			}

			// copy controls
			for (int k = 1; k < src.getChildCount(); k += 2) {
				TimeButton btn_src = (TimeButton) src.getChildAt(k);
				TimeButton btn_dst = new TimeButton(Thermostat.this);
				btn_dst.setTime(btn_src.getTime());
				btn_dst.setText();
				row.addView(btn_dst);

				EditText et_src = (EditText) src.getChildAt(k + 1);
				row.addView(createTemp(et_src.getText().toString()));
			}

			// fix focus
			if (i > row.getChildCount()) {
				i = row.getChildCount() - 1;
			}
			row.getChildAt(i).requestFocus();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	// handle the heating/cooling tabs, and is totally the wrong way to do it, but I didn't feel like dealing with fragments
	class ProgramTabListener<T extends Fragment> implements TabListener {
		LinearLayout mCtrl;
		TableLayout mTbl;
		Button mBtn;
		String mPath;
		String mPrompt;

		public ProgramTabListener(int ctrl, int btn, int tbl, String path, String prompt) {
			mCtrl = (LinearLayout) findViewById(ctrl);
			mTbl = (TableLayout) findViewById(tbl);
			mBtn = (Button) findViewById(btn);
			mPath = path;
			mPrompt = prompt;
		}

		public void onTabSelected(Tab tab, FragmentTransaction ft) {
			mCtrl.setVisibility(View.VISIBLE);
			if (mTbl.getChildCount() == 0) {
				new FetchProgram().execute(mPath, mPrompt);
			}

			// set up action for update button
			mBtn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					updateProgram(mPath, mTbl);
				}
			});
		}

		// hide unselected tab
		public void onTabUnselected(Tab tab, FragmentTransaction ft) {
			mCtrl.setVisibility(View.GONE);
		}

		// reload if necessary
		public void onTabReselected(Tab tab, FragmentTransaction ft) {
			onTabSelected(tab, ft);
		}
	}

	// handle the status tab
	class StatusTabListener<T extends Fragment> implements TabListener {
		LinearLayout mCtrl;
		TableLayout mTbl;
		Button mBtn;
		String mPath;
		String mPrompt;

		public StatusTabListener(int ctrl) {
			mCtrl = (LinearLayout) findViewById(ctrl);
		}

		public void onTabSelected(Tab tab, FragmentTransaction ft) {
			mCtrl.setVisibility(View.VISIBLE);
		}

		// hide unselected tab
		public void onTabUnselected(Tab tab, FragmentTransaction ft) {
			mCtrl.setVisibility(View.GONE);
		}

		public void onTabReselected(Tab tab, FragmentTransaction ft) {
		}
	}

	// discover a thermostat on the network using Simple Service Discovery Protocol
	// note that "onPostExecute" is not used because we call "task.get()"
	class Discover extends AsyncTask<Void, Void, String> {
		@Override
		protected String doInBackground(Void... params) {
			DatagramSocket sock = null;

			netLock.lock();
			try {
				// lock Wi-Fi on so we don't miss the multicast response
				WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
				WifiManager.MulticastLock multicastLock = wm.createMulticastLock("mylock");
				multicastLock.acquire();

				// send discovery packet
				InetAddress dest = InetAddress.getByName("239.255.255.250");
				String data = "TYPE: WM-DISCOVER\r\nVERSION: 1.0\r\n\r\nservices: com.marvell.wm.system*\r\n\r\n";
				byte[] sendData = data.getBytes();
				DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, dest, 1900);
				sock = new DatagramSocket(8888);
				sock.send(sendPacket);

				// receive response
				// wait a maximum of 2 seconds
				sock.setSoTimeout(2000);
				byte[] receiveData = new byte[4096];
				DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
				sock.receive(receivePacket);
				String resp = new String(receivePacket.getData());

				// unlock Wi-Fi so it can powersave
				multicastLock.release();

				// parse the IP address out of the response, which looks like:
				// TYPE: WM-NOTIFY
				// VERSION: 1.0
				//
				// SERVICE: com.marvell.wm.system:1.0
				// LOCATION: http://192.168.1.4/sys/
				int i = resp.indexOf("//");
				if (i == -1) {
					return "*Can't parse location result";
				}
				int j = resp.indexOf("/", i + 2);
				if (j == -1) {
					return "*Can't parse location result";
				}
				return resp.substring(i + 2, j);
			} catch (Exception e) {
				return "*Thermostat not found\n\n" + e;
			} finally {
				try {
					sock.close();
				} catch (Exception e) {
				}
				netLock.unlock();
			}
		}
	}

	// generic class to read stuff from thermostat
	// takes 2 arguments: URL path, and status message to be displayed while running
	// all the subclasses implement onPostExecute() so we don't even try
	class ReadURL extends AsyncTask<String, String, Void> {
		String error = null;
		String path;
		JSONObject json = null;
		StringBuilder data = new StringBuilder();

		@Override
		protected Void doInBackground(String... params) {
			netLock.lock();
			try {
				path = params[0];
				publishProgress(params[1]);

				// check to see if we have this data already
				if ((state_old != null) && (state_old.containsKey(path))) {
					json = new JSONObject(state_old.getString(path));
					return null;
				}

				URL url = new URL("http://" + addr + "/" + path);
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();

				// java is not quite as bad as COBOL or FORTRAN at reading data, but it's getting there
				BufferedReader output = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				String line;
				while ((line = output.readLine()) != null) {
					data.append(line + "\n");
				}

				int resp = conn.getResponseCode();
				if (resp != 200) {
					error = "HTTP response: " + resp;
					return null;
				}
				json = new JSONObject(data.toString());
				conn.disconnect();
			} catch (Exception e) {
				error = e.toString();
			} finally {
				netLock.unlock();
			}
			return null;
		}

		@Override
		protected void onProgressUpdate(String... values) {
			setProgressBarIndeterminateVisibility(true);
			status(values[0]);
		}
	}

	// generic class to write stuff to thermostat
	// takes 3 arguments: URL path, data string, and status message to be displayed while running
	class WriteURL extends AsyncTask<String, String, Void> {
		String error = null;

		@Override
		protected Void doInBackground(String... params) {
			netLock.lock();
			try {
				publishProgress(params[2]);
				URL url = new URL("http://" + addr + "/" + params[0]);
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setDoOutput(true);
				conn.setRequestMethod("POST");
				conn.setRequestProperty("Accept", "text/plain");
				conn.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
				conn.getOutputStream().write(params[1].getBytes());

				int resp = conn.getResponseCode();
				if (resp != 200) {
					error = "HTTP response: " + resp;
					return null;
				}
				conn.disconnect();
			} catch (Exception e) {
				error = e.toString();
			} finally {
				netLock.unlock();
			}
			return null;
		}

		// the action usually happens so fast that we don't put up a progress indicator
		@Override
		protected void onProgressUpdate(String... values) {
			status(values[0]);
		}

		// display an error if we get one
		protected void onPostExecute(Void result) {
			if (error != null) {
				status(error);
				return;
			}
			status("");
		}
	}

	// set up status tab
	class FetchStatus extends ReadURL {
		@Override
		protected void onPostExecute(Void result) {
			setProgressBarIndeterminateVisibility(false);
			if (error != null) {
				status(error);
				return;
			}
			try {
				// this will set the spinner, ignore that change
				oldMode = json.getInt("tmode");
				mode.setSelection(oldMode);

				// current time
				if (json.has("time")) {
					JSONObject time = json.getJSONObject("time");
					int hour = time.getInt("hour");
					String ampm = "am";
					if (hour > 12) {
						hour -= 12;
						ampm = "pm";
					}
					status_time.setText(String.format("%s %d:%02d %s", days[time.getInt("day")], hour, time.getInt("minute"), ampm));
					status_time_set.setEnabled(true);
				}

				// current temperature
				if (json.has("temp")) {
					status_temp.setText(String.format("%.1f\u00B0", json.getDouble("temp")));
				}

				// target temperature
				Double target = null;
				if (json.has("t_cool")) {
					target = Double.valueOf(json.getDouble("t_cool"));
					targetKey = "t_cool";
				}
				if (json.has("t_heat")) {
					target = Double.valueOf(json.getDouble("t_heat"));
					targetKey = "t_heat";
				}
				if (target != null) {
					status_target.setText(String.format("%.0f\u00B0", target));
					status_incr.setEnabled(true);
					status_decr.setEnabled(true);
					status_hold.setEnabled(true);
					oldTarget = target;
					currentTarget = target;
				} else {
					status_target.setText("(None)");
					status_incr.setEnabled(false);
					status_decr.setEnabled(false);
					status_set.setEnabled(false);
					status_hold.setEnabled(false);
				}
				status_set.setEnabled(false);

				// show override flag
				if (json.has("override")) {
					if (json.getInt("override") == 0) {
						status_override.setVisibility(View.GONE);
					} else {
						status_override.setVisibility(View.VISIBLE);
					}
				}

				// hold mode
				if (json.has("hold")) {
					oldHold = (json.getInt("hold") != 0);
					status_hold.setChecked(oldHold);
				}

				// fan
				if (json.has("fmode")) {
					status_fan.setSelection(json.getInt("fmode"));
				}
				if (json.has("fstate")) {
					if (json.getInt("fstate") == 0) {
						status_fan_icon.setVisibility(View.GONE);
					} else {
						status_fan_icon.setVisibility(View.VISIBLE);
					}
				}
			} catch (Exception e) {
				status(e);
				return;
			}
			status("");
		}
	}

	// fetch the program for this tab from the thermostat and display it
	class FetchProgram extends ReadURL {
		TimeButton btn;
		TableRow row;
		TextView tv;
		private TableLayout tbl;

		@Override
		protected void onPostExecute(Void result) {
			setProgressBarIndeterminateVisibility(false);
			if (error != null) {
				status(error);
				return;
			}
			// draw the GUI
			state_new.put(path, json);
			try {
				if (path.endsWith("cool")) {
					tbl = (TableLayout) findViewById(R.id.cool_table);
				} else {
					tbl = (TableLayout) findViewById(R.id.heat_table);
				}
				// create each row
				int ndx = 0;
				for (String day : days) {
					row = new TableRow(Thermostat.this);
					tv = new TextView(Thermostat.this);
					tv.setText(day);
					row.addView(tv);

					// create each temperature control in this row
					JSONArray pgm = json.getJSONArray(ndx + "");
					for (int i = 0; i < pgm.length(); i += 2) {
						// Warning: "getApplicationContext()" doesn't work here, you must use "Thermostat.this" or the
						// TimePickerDialog will take a big 'ol crap - and I don't know why!
						btn = new TimeButton(Thermostat.this);
						btn.setTime(pgm.getInt(i));
						btn.setText();
						row.addView(btn);

						String temp = pgm.getString(i + 1);
						row.addView(createTemp(temp));
					}
					tbl.addView(row);
					ndx++;
				}
			} catch (Exception e) {
				status(e);
				return;
			}
			status("");
		}
	}

	// write a new program from the tab's controls and send it to the thermostat
	void updateProgram(String path, TableLayout tbl) {
		JSONObject json = new JSONObject();
		String temp = "";

		// iterate through rows in table
		for (int i = 0; i < tbl.getChildCount(); i++) {
			TableRow row = (TableRow) tbl.getChildAt(i);
			JSONArray jarray = new JSONArray();
			// iterate through controls in a row, two at a time
			for (int j = 1; j < row.getChildCount(); j += 2) {
				TimeButton btn = (TimeButton) row.getChildAt(j);
				EditText et = (EditText) row.getChildAt(j + 1);
				jarray.put(btn.getTime());
				try {
					temp = et.getText().toString().trim();
					jarray.put(Integer.parseInt(temp));
				} catch (Exception e) {
					status("Error: \"" + temp + "\" is not a valid temperature");
					return;
				}
			}
			try {
				json.put(i + "", jarray);
			} catch (Exception e) {
				status(e);
				return;
			}
		}

		// do it to it
		state_new.put(path, json);
		new WriteURL().execute(path, json.toString(), "Sending program");
	}

	// create & populate new temperature control
	EditText createTemp(String temp) {
		EditText et;

		et = new EditText(this);
		et.setText(" " + temp);
		et.setInputType(InputType.TYPE_CLASS_PHONE);
		et.addTextChangedListener(new TextWatcher() {
			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			// use * and # (or - and +) as decrement/increment keys
			@Override
			public void afterTextChanged(Editable s) {
				if (s.toString().contains("-")) {
					changeTemp("-", -1, s);
				}
				if (s.toString().contains("*")) {
					changeTemp("*", -1, s);
				}
				if (s.toString().contains("+")) {
					changeTemp("+", 1, s);
				}
				if (s.toString().contains("#")) {
					changeTemp("#", 1, s);
				}
			}
		});
		return et;
	}

	// increment/decrement a program temperature value
	void changeTemp(String c, int dir, Editable s) {
		String str = s.toString().replace(c, "");
		int num = Integer.parseInt(str);
		num = num + dir;
		s.clear();
		s.append(num + "");
	}

	// focus new blank program temperature control that's the same size as current controls
	EditText createTemp(int width) {
		EditText et;

		et = createTemp("");
		et.setWidth(width);
		et.requestFocus();
		return et;
	}

	// increment/decrement target temperature display
	void changeTarget(int dir) {
		currentTarget += dir;
		status_target.setText(String.format("%.0f\u00B0", currentTarget));
		status_set.setEnabled(currentTarget != oldTarget);
	}

	// display status message
	void status(String m) {
		msg_line.setText(m);
	}

	// report errors
	void status(Exception e) {
		StackTraceElement[] st = e.getStackTrace();
		String where = st[0].toString();
		String pkg = this.getClass().getPackage().getName();
		where = where.replace(pkg + ".", "");
		msg_line.setText("Error: " + e + "\n" + where);
	}
}
