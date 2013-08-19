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
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.ToggleButton;

public class Thermostat extends Activity {
	// persistent preferences values
	private static final String PREFS_NAME = "ThermostatPrefs";
	protected static final String PREF_TAB = "Tab";

	// menu constants
	private static final int MENU_DELETE = 0;
	private static final int MENU_ADD_BEFORE = 1;
	private static final int MENU_ADD_AFTER = 2;
	private static final int MENU_COPY_ABOVE = 3;
	private static final int MENU_COPY_BELOW = 4;
	private static final int MENU_REFRESH = 5;
	private static final int MENU_RESCAN = 6;
	private static final int MENU_SETADDRESS = 7;
	private static final int MENU_SYNCTIME = 8;

	// bundle keys
	private static final String ADDR_KEY = "addr";
	private static final String TIME_KEY = "time";

	// reload status page if it's older than this many milliseconds (5 minutes)
	static final int STATUS_TIMEOUT = 5 * 60 * 1000;

	// pause this many milliseconds between operations
	static final int OPERATION_TIMEOUT = 1500;

	// controls
	static boolean oldHold;
	static double oldTarget;
	static double currentTarget;

	// thermostat network address
	static String addr = null;
	static String targetKey;

	// status message at the bottom
	static TextView msg_line;

	// status tab controls
	static Spinner mode;
	static Spinner statusFan;
	static TextView statusAddr;
	static TextView statusTime;
	static TextView statusTemp;
	static TextView statusTarget;
	static TextView statusState;
	static TextView statusOverride;
	static Button statusTempIncr;
	static Button statusTempDecr;
	static Button statusTempSet;
	static Button statusRefresh;
	static ToggleButton statusTempHold;
	static ImageView statusFanIcon;
	static ArrayAdapter<CharSequence> adapterMode;
	static ArrayAdapter<CharSequence> adapterFan;

	// tab bar
	ActionBar actionBar = null;
	TabListener<CoolProgramTab> coolTabListener;
	TabListener<HeatProgramTab> heatTabListener;

	// the thermostat is single-threaded, this ensures we stick to that
	final ReentrantLock netLock = new ReentrantLock();

	// old and new control states
	static Bundle state_old;
	final static HashMap<String, JSONObject> state_new = new HashMap<String, JSONObject>();

	// last time the status page was displayed
	long statusDisplay = 0;

	// last time we finished an operation to the thermostat
	long operationTime = 0;

	SimpleDateFormat prettyFormat = new SimpleDateFormat("hh:mm a", Locale.US);
	Calendar cal = prettyFormat.getCalendar();
	// no clue how to get these in a localized manner, so I hardcoded them
	String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };
	static Thermostat thermostatContext;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		setContentView(R.layout.thermostat);

		SharedPreferences sSettings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
		state_old = savedInstanceState;

		thermostatContext = this;

		// find controls
		msg_line = (TextView) findViewById(R.id.status);

		// locate thermostat on the network
		if (sSettings.contains(ADDR_KEY)) {
			addr = sSettings.getString(ADDR_KEY, "Unknown");
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
			}
		}

		// set up action bar for tabs
		actionBar = getActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

		actionBar.addTab(actionBar.newTab().setText("Status").setTabListener(new TabListener<StatusTab>(this, StatusTab.class)));
		coolTabListener = new TabListener<CoolProgramTab>(this, CoolProgramTab.class);
		actionBar.addTab(actionBar.newTab().setText("Cooling").setTabListener(coolTabListener));
		heatTabListener = new TabListener<HeatProgramTab>(this, HeatProgramTab.class);
		actionBar.addTab(actionBar.newTab().setText("Heating").setTabListener(heatTabListener));

		// set up mode spinner appearance and content
		adapterMode = ArrayAdapter.createFromResource(this, R.array.modes, R.layout.largespinner);
		adapterMode.setDropDownViewResource(R.layout.largespinner);

		// set up fan spinner appearance and content
		adapterFan = ArrayAdapter.createFromResource(this, R.array.fan, R.layout.largespinner);
		adapterFan.setDropDownViewResource(R.layout.largespinner);
	}

	// this is called if we pop out and back in, so we have to see if the status page is being viewed and if it needs to be refreshed
	@Override
	protected void onRestart() {
		super.onRestart();
		if ((actionBar.getSelectedNavigationIndex() == 0) && (statusDisplay < System.currentTimeMillis() - STATUS_TIMEOUT)) {
			thermostatContext.new FetchStatus().execute("tstat", "Loading status");
		}
	}

	// stolen from the Android Developer Action Bar API Guide
	public static class TabListener<T extends Fragment> implements ActionBar.TabListener {
		private Fragment mFragment;
		private final Activity mActivity;
		private final Class<T> mClass;

		public TabListener(Activity a, Class<T> c) {
			mActivity = a;
			mClass = c;
		}

		@Override
		public void onTabSelected(Tab tab, FragmentTransaction ft) {
			// Check if the fragment is already initialized
			if (mFragment == null) {
				// If not, instantiate and add it to the activity
				mFragment = Fragment.instantiate(mActivity, mClass.getName());
				ft.add(R.id.container, mFragment);
			} else {
				// If it exists, simply attach it in order to show it
				ft.attach(mFragment);
			}
		}

		@Override
		public void onTabUnselected(Tab tab, FragmentTransaction ft) {
			if (mFragment != null) {
				// close the damned keyboard
				InputMethodManager imm = (InputMethodManager) msg_line.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
				imm.hideSoftInputFromWindow(msg_line.getWindowToken(), 0);

				// Detach the fragment, because another one is being attached
				ft.detach(mFragment);
			}
		}

		@Override
		public void onTabReselected(Tab tab, FragmentTransaction ft) {
			// User selected the already selected tab. Usually do nothing.
		}
	}

	// squirrel away our hard-earned data
	@Override
	protected void onSaveInstanceState(Bundle outState) {
		SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, 0).edit();

		// save thermostat address as a preference so it stays around
		if (addr != null) {
			editor.putString(ADDR_KEY, addr);
		}

		// save currently selected tab
		editor.putInt(PREF_TAB, getActionBar().getSelectedNavigationIndex());

		editor.commit();

		// save cooling/heating programs in temporary state
		for (String s : state_new.keySet()) {
			outState.putString(s, state_new.get(s).toString());
		}
		outState.putLong(TIME_KEY, statusDisplay);
	}

	// restore the current tab position
	@Override
	public void onRestoreInstanceState(Bundle savedInstanceState) {
		statusDisplay = savedInstanceState.getLong(TIME_KEY);
		SharedPreferences pref = getSharedPreferences(PREFS_NAME, 0);
		getActionBar().setSelectedNavigationItem(pref.getInt(PREF_TAB, 0));
	}

	// create options menu before it is shown
	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		menu.clear();

		// these only make sense if we have a focused control
		View v = getCurrentFocus();
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
		menu.add(Menu.NONE, MENU_RESCAN, Menu.NONE, "Rescan for thermostat IP address");
		menu.add(Menu.NONE, MENU_SETADDRESS, Menu.NONE, "Input thermostat IP address");
		menu.add(Menu.NONE, MENU_SYNCTIME, Menu.NONE, "Sync thermostat time to phone");
		menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, "Refresh programs");
		return true;
	}

	// handle selection from options menu
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int choice = item.getItemId();

		// delete old controls and refetch data
		if (choice == MENU_REFRESH) {
			// switch away from heat/cool tabs so that layouts are detached & destroyed
			int cur = actionBar.getSelectedNavigationIndex();
			actionBar.setSelectedNavigationItem(0);

			// drop the instantiated classes
			coolTabListener.mFragment = null;
			heatTabListener.mFragment = null;

			// reload tab by switching back to it
			if (cur != 0) {
				actionBar.setSelectedNavigationItem(cur);
			}
			return true;
		}

		// sync thermostat time to phone
		if (choice == MENU_SYNCTIME) {
			JSONObject json = new JSONObject();
			JSONObject time = new JSONObject();
			try {
				time.put("day", cal.get(Calendar.DAY_OF_WEEK));
				time.put("hour", cal.get(Calendar.HOUR_OF_DAY));
				time.put("minute", cal.get(Calendar.MINUTE));
				json.put("time", time);
			} catch (Exception e) {
				status(e);
				return true;
			}
			new WriteURL().execute("tstat", json.toString(), "Setting time");
			return true;
		}

		// rescan for thermostat IP address
		if (choice == MENU_RESCAN) {
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
			} else {
				// status_addr.setText(addr);
				new FetchStatus().execute("tstat", "Loading status");
			}
		}

		// have user enter thermostat network address
		if (choice == MENU_SETADDRESS) {
			AlertDialog.Builder builder = new AlertDialog.Builder(Thermostat.this);
			builder.setTitle("Enter dotted IP address or DNS name");
			LayoutInflater inflater = getLayoutInflater();
			View view = inflater.inflate(R.layout.address, null);
			builder.setView(view);
			final EditText et = (EditText) view.findViewById(R.id.address);
			et.setText(addr);

			builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// since it could be any local DNS name, there's not much we can do to validate the entry
					// we have to trust the user here
					addr = et.getText().toString().trim();
					// status_addr.setText(addr);
					new FetchStatus().execute("tstat", "Loading status");
				}
			});

			builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.cancel();
				}
			});

			AlertDialog alert = builder.create();
			alert.show();
		}

		// do something with/to a focused control
		View v = getCurrentFocus();
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

	// handle the heating/cooling tabs
	public static class ProgramTab extends Fragment {
		View layout = null;
		String path = null;
		TableLayout tbl;
		Button btn;
		Button cur_down;
		Button cur_up;
		Button cur_left;
		Button cur_right;

		@Override
		public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
			if (layout == null) {
				layout = inflater.inflate(R.layout.program_tab, container, false);
			} else {
				// already initialized
				return layout;
			}

			// find controls
			tbl = (TableLayout) layout.findViewById(R.id.program_table);
			btn = (Button) layout.findViewById(R.id.update);
			cur_down = (Button) layout.findViewById(R.id.cur_down);
			cur_up = (Button) layout.findViewById(R.id.cur_up);
			cur_left = (Button) layout.findViewById(R.id.cur_left);
			cur_right = (Button) layout.findViewById(R.id.cur_right);

			// set up action for update button
			btn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					updateProgram(path, tbl);
				}
			});

			// move cursor down to next program control
			cur_down.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					move(layout, View.FOCUS_DOWN, 0);
				}
			});

			// move cursor up to next program control
			cur_up.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					move(layout, View.FOCUS_UP, 0);
				}
			});

			// move cursor left, to next program control if necessary
			cur_left.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					move(layout, View.FOCUS_LEFT, -1);
				}
			});

			// move cursor right, to next program control if necessary
			cur_right.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					move(layout, View.FOCUS_RIGHT, 1);
				}
			});

			// fetch current program
			String prompt = "Loading cooling program";
			if (path.equals("tstat/program/heat")) {
				prompt = "Loading heating program";
			}
			thermostatContext.new FetchProgram().execute(path, prompt, tbl);

			return layout;
		}
	}

	public static class CoolProgramTab extends ProgramTab {
		public CoolProgramTab() {
			path = "tstat/program/cool";
		}
	}

	public static class HeatProgramTab extends ProgramTab {
		public HeatProgramTab() {
			path = "tstat/program/heat";
		}
	}

	// handle the status tab
	public static class StatusTab extends Fragment {
		View layout = null;
		// bugfix: spinner gets selected multiple times during initialization
		int modeInitCtr = 2;
		int fanInitCtr = 1;

		@Override
		public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
			if (layout == null) {
				layout = inflater.inflate(R.layout.status_tab, container, false);
			} else {
				// already initialized, may need to be refreshed
				if (thermostatContext.statusDisplay < System.currentTimeMillis() - STATUS_TIMEOUT) {
					thermostatContext.new FetchStatus().execute("tstat", "Loading status");
				}
				return layout;
			}

			// find controls
			mode = (Spinner) layout.findViewById(R.id.mode);
			statusAddr = (TextView) layout.findViewById(R.id.status_addr);
			statusTime = (TextView) layout.findViewById(R.id.status_time);
			statusTemp = (TextView) layout.findViewById(R.id.status_temp);
			statusTarget = (TextView) layout.findViewById(R.id.status_target);
			statusState = (TextView) layout.findViewById(R.id.status_state);
			statusOverride = (TextView) layout.findViewById(R.id.status_override);
			statusTempIncr = (Button) layout.findViewById(R.id.status_temp_incr);
			statusTempDecr = (Button) layout.findViewById(R.id.status_temp_decr);
			statusTempSet = (Button) layout.findViewById(R.id.status_temp_set);
			statusRefresh = (Button) layout.findViewById(R.id.status_refresh);
			statusTempHold = (ToggleButton) layout.findViewById(R.id.status_temp_hold);
			statusFanIcon = (ImageView) layout.findViewById(R.id.status_fan_icon);
			statusFan = (Spinner) layout.findViewById(R.id.status_fan);

			mode.setAdapter(adapterMode);

			// update mode when it changes
			mode.setOnItemSelectedListener(new OnItemSelectedListener() {
				@Override
				public void onItemSelected(AdapterView<?> arg0, View v, int position, long id) {
					// bugfix: spinner gets selected multiple times during initialization
					if (modeInitCtr > 0) {
						modeInitCtr--;
						return;
					}

					JSONObject json = new JSONObject();
					try {
						json.put("tmode", mode.getSelectedItemPosition());
					} catch (Exception e) {
						status(e);
						return;
					}
					thermostatContext.new WriteURL().execute("tstat", json.toString(), "Setting mode");
				}

				@Override
				public void onNothingSelected(AdapterView<?> arg0) {
				}
			});

			// button to increment target temperature
			statusTempIncr.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					changeTarget(1);
				}
			});

			// button to decrement target temperature
			statusTempDecr.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					changeTarget(-1);
				}
			});

			// button to set new temperature target
			statusTempSet.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					JSONObject json = new JSONObject();
					try {
						json.put(targetKey, currentTarget);
					} catch (Exception e) {
						status(e);
						return;
					}
					thermostatContext.new WriteURL().execute("tstat", json.toString(), "Setting new temperature target");
				}
			});

			// update hold when it toggles
			oldHold = false;
			statusTempHold.setOnCheckedChangeListener(new OnCheckedChangeListener() {
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
						thermostatContext.new WriteURL().execute("tstat", json.toString(), "Setting hold mode");
					}
				}
			});

			statusFan.setAdapter(adapterFan);

			// update fan when it changes
			statusFan.setOnItemSelectedListener(new OnItemSelectedListener() {
				@Override
				public void onItemSelected(AdapterView<?> arg0, View v, int position, long id) {
					// bugfix: spinner gets selected multiple times during initialization
					if (fanInitCtr > 0) {
						fanInitCtr--;
						return;
					}

					JSONObject json = new JSONObject();
					try {
						json.put("fmode", statusFan.getSelectedItemPosition());
					} catch (Exception e) {
						status(e);
						return;
					}
					thermostatContext.new WriteURL().execute("tstat", json.toString(), "Setting fan");
				}

				@Override
				public void onNothingSelected(AdapterView<?> arg0) {
				}
			});

			// button to refresh status tab
			statusRefresh.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (state_old != null) {
						state_old.remove("tstat");
					}
					thermostatContext.new FetchStatus().execute("tstat", "Loading status");
				}
			});

			// initial load of status page
			if (addr != null) {
				thermostatContext.new FetchStatus().execute("tstat", "Loading status");
			}
			return layout;
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
			if (System.currentTimeMillis() < operationTime + OPERATION_TIMEOUT) {
				// the thermostat is much happer if we give it a little time between operations
				pause(OPERATION_TIMEOUT);
			}
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
			operationTime = System.currentTimeMillis();
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
			if (state_old != null) {
				// remove stale value from cache
				state_old.remove(params[0]);
			}
			netLock.lock();
			if (System.currentTimeMillis() < operationTime + OPERATION_TIMEOUT) {
				// the thermostat is much happer if we give it a little time between operations
				pause(OPERATION_TIMEOUT);
			}
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
			operationTime = System.currentTimeMillis();
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
			new FetchStatus().execute("tstat", "Loading status");
		}
	}

	// set up status tab
	class FetchStatus extends ReadURL {
		@Override
		protected void onPreExecute() {
			// disable button so user can't hammer on it
			statusRefresh.setEnabled(false);
		}

		@Override
		protected void onPostExecute(Void result) {
			setProgressBarIndeterminateVisibility(false);
			statusRefresh.setEnabled(true);
			if (error != null) {
				status(error);
				return;
			}
			state_new.put(path, json);

			// save fetch time
			statusDisplay = System.currentTimeMillis();

			try {
				// this will set the spinner, ignore that change
				int oldMode = json.getInt("tmode");
				OnItemSelectedListener oldListener = mode.getOnItemSelectedListener();
				mode.setOnItemSelectedListener(null);
				mode.setSelection(oldMode);
				mode.setOnItemSelectedListener(oldListener);

				// IP address
				statusAddr.setText(addr);

				// current time
				if (json.has("time")) {
					JSONObject time = json.getJSONObject("time");
					int hour = time.getInt("hour");
					String ampm = "am";
					if (hour == 0) {
						hour = 12;
					}
					if (hour > 12) {
						hour -= 12;
						ampm = "pm";
					}
					statusTime.setText(String.format("%s %d:%02d %s", days[time.getInt("day")], hour, time.getInt("minute"), ampm));
				}

				// current temperature
				if (json.has("temp")) {
					statusTemp.setText(String.format("%.1f\u00B0", json.getDouble("temp")));
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
					statusTarget.setText(String.format("%.0f\u00B0", target));
					statusTempIncr.setEnabled(true);
					statusTempDecr.setEnabled(true);
					statusTempHold.setEnabled(true);
					oldTarget = target;
					currentTarget = target;
				} else {
					statusTarget.setText("(None)");
					statusTempIncr.setEnabled(false);
					statusTempDecr.setEnabled(false);
					statusTempHold.setEnabled(false);
				}
				statusTempSet.setEnabled(false);

				// show override flag
				if (json.has("override")) {
					if (json.getInt("override") == 0) {
						statusOverride.setVisibility(View.GONE);
					} else {
						statusOverride.setVisibility(View.VISIBLE);
					}
				}

				// hold mode
				if (json.has("hold")) {
					oldHold = (json.getInt("hold") != 0);
					statusTempHold.setChecked(oldHold);
				}

				// fan
				if (json.has("fmode")) {
					// this will set the spinner, ignore that change
					oldListener = statusFan.getOnItemSelectedListener();
					statusFan.setOnItemSelectedListener(null);
					statusFan.setSelection(json.getInt("fmode"));
					statusFan.setOnItemSelectedListener(oldListener);
				}
				if (json.has("fstate")) {
					if (json.getInt("fstate") == 0) {
						statusFanIcon.setVisibility(View.GONE);
					} else {
						statusFanIcon.setVisibility(View.VISIBLE);
					}
				}

				// current state
				if (json.has("tstate")) {
					switch (json.getInt("tstate")) {
					case 0:
						statusState.setText("Off");
						break;
					case 1:
						statusState.setText("Heat");
						break;
					case 2:
						statusState.setText("Cool");
						break;
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
		TableLayout tbl;

		public void execute(String path, String prompt, TableLayout tbl) {
			this.tbl = tbl;
			super.execute(path, prompt);
		}

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
	static void updateProgram(String path, TableLayout tbl) {
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
		thermostatContext.new WriteURL().execute(path, json.toString(), "Sending program");
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
	void changeTemp(String c, int dir, Editable ctrl) {
		int num;
		String str = ctrl.toString().replace(c, "").trim();
		try {
			num = Integer.parseInt(str);
		} catch (Exception e) {
			status("Error: \"" + str + "\" is not a valid temperature");
			return;
		}
		num = num + dir;
		ctrl.clear();
		ctrl.append(num + "");
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
	static void changeTarget(int dir) {
		currentTarget += dir;
		statusTarget.setText(String.format("%.0f\u00B0", currentTarget));
		statusTempSet.setEnabled(currentTarget != oldTarget);
	}

	// move cursor from one program temperature control to another
	public static void move(View layout, int dir, int subdir) {
		View t = layout.findFocus();
		if (t == null) {
			return;
		}

		if (t instanceof EditText) {
			if (subdir != 0) {
				// move cursor within field if possible
				EditText t2 = (EditText) t;
				int cur = t2.getSelectionStart() + subdir;
				if (cur >= 0 && cur <= t2.getText().length()) {
					t2.setSelection(cur);
					return;
				}
			}
			View c = t.focusSearch(dir);
			if (c == null) {
				return;
			}
			if (c instanceof EditText) {
				c.requestFocus();
			}
		}
	}

	// sleep
	void pause(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			status(e);
		}
	}

	// display status message
	static void status(String m) {
		msg_line.setText(m);
	}

	// report errors
	static void status(Exception e) {
		String msg = "";
		for (StackTraceElement elem : e.getStackTrace()) {
			msg += "\n" + elem.toString();
		}
		msg_line.setText("Error: " + e + msg);
	}
}
