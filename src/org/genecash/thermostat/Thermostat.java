package org.genecash.thermostat;

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

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.ActionBar;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

public class Thermostat extends Activity {
	// menu constants
	private static final int MENU_DELETE = 0;
	private static final int MENU_ADD_BEFORE = 1;
	private static final int MENU_ADD_AFTER = 2;
	private static final int MENU_COPY_ABOVE = 3;
	private static final int MENU_COPY_BELOW = 4;
	private static final int MENU_REFRESH = 5;

	// bundle keys
	private static final String ADDR_KEY = "addr";
	private static final String TAB_KEY = "tab";

	int oldMode;
	String addr = null;
	Spinner mode;
	TextView msg_line;
	ActionBar actionBar = null;

	// the thermostat is single-threaded, this ensures we stick to that
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

		state_old = savedInstanceState;

		msg_line = (TextView) findViewById(R.id.status);

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
		tab.setText("Cooling");
		tab.setTabListener(new MyTabListener<Fragment>(R.id.layout_cool, R.id.update_cool, R.id.table_cool, "tstat/program/cool",
				"Loading cooling program"));
		actionBar.addTab(tab);

		// create 2nd tab
		tab = actionBar.newTab();
		tab.setText("Heating");
		tab.setTabListener(new MyTabListener<Fragment>(R.id.layout_heat, R.id.update_heat, R.id.table_heat, "tstat/program/heat",
				"Loading heating program"));
		actionBar.addTab(tab);

		// reselect the previously selected tab
		if ((state_old != null) && (state_old.containsKey(TAB_KEY)) && (state_old.getString(TAB_KEY).equals(tab.getText().toString()))) {
			tab.select();
		}

		// create mode spinner
		oldMode = -1;
		String[] modes = { "Off", "Heat", "Cool", "Auto", "" };
		mode = (Spinner) findViewById(R.id.mode);
		ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, modes);
		mode.setAdapter(modeAdapter);
		// blank until it's set
		mode.setSelection(4);

		// update mode when it changes
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
					status(e.toString());
					return;
				}
				new WriteURL().execute("tstat", json.toString(), "Setting mode");
			}

			@Override
			public void onNothingSelected(AdapterView<?> arg0) {
			}
		});

		// load mode spinner
		new FetchMode().execute("tstat", "Loading mode");
	}

	// display status message
	void status(String m) {
		if (m == null) {
			msg_line.setText("");
		} else {
			msg_line.setText(m);
		}
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
		// save currently selected tab
		if (actionBar != null) {
			outState.putString(TAB_KEY, actionBar.getSelectedTab().getText().toString());
		}
	}

	// this switches between the tabs, and is totally the wrong way to do it, but I didn't feel like dealing with fragments
	class MyTabListener<T extends Fragment> implements TabListener {
		LinearLayout mCtrl;
		TableLayout mTbl;
		Button mBtn;
		String mPath;
		String mPrompt;

		public MyTabListener(int ctrl, int btn, int tbl, String path, String prompt) {
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

		public void onTabUnselected(Tab tab, FragmentTransaction ft) {
			mCtrl.setVisibility(View.GONE);
		}

		public void onTabReselected(Tab tab, FragmentTransaction ft) {
			// reload if necessary
			onTabSelected(tab, ft);
		}
	}

	// discover a thermostat on the network using Simple Service Discovery Protocol
	// note that "onPostExecute" is useless because we call "task.get()"
	class Discover extends AsyncTask<Void, Void, String> {
		@Override
		protected String doInBackground(Void... params) {
			DatagramSocket sock = null;

			netLock.lock();
			try {
				// lock WiFi on so we don't miss the multicast response
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

				// unlock WiFi so it can powersave
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
	// takes 2 arguments: URL path, and status message
	// all the subclasses implement onPostExecute() so we don't even try
	class ReadURL extends AsyncTask<String, String, Void> {
		String error = null;
		String path;
		JSONObject json = null;
		StringBuilder bagOfShit = new StringBuilder();

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
				BufferedReader shit = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				String moreShit;
				while ((moreShit = shit.readLine()) != null) {
					bagOfShit.append(moreShit + "\n");
				}

				int resp = conn.getResponseCode();
				if (resp != 200) {
					error = "HTTP response: " + resp;
					return null;
				}
				json = new JSONObject(bagOfShit.toString());
				conn.disconnect();
			} catch (Exception e) {
				error = e.toString();
			} finally {
				netLock.unlock();
			}
			// why are Void and void different? because java's design is broken
			return null;
		}

		@Override
		protected void onProgressUpdate(String... values) {
			setProgressBarIndeterminateVisibility(true);
			status(values[0]);
		}
	}

	// generic class to write stuff to thermostat
	// takes 3 arguments: URL path, data string, and status message
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
			// why are Void and void different? because java's design is broken
			return null;
		}

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
			status(null);
		}
	}

	// set thermostat mode spinner
	class FetchMode extends ReadURL {
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
			} catch (Exception e) {
				status(e.toString());
				return;
			}
			status(null);
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
					tbl = (TableLayout) findViewById(R.id.table_cool);
				} else {
					tbl = (TableLayout) findViewById(R.id.table_heat);
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
				status(e.toString());
				return;
			}
			status(null);
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
				status(e.toString());
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
		et.setText(temp);
		et.setInputType(InputType.TYPE_CLASS_PHONE);
		return et;
	}

	// focus new blank temperature control that's the same size as current controls
	EditText createTemp(int width) {
		EditText et;

		et = createTemp("");
		et.setWidth(width);
		et.requestFocus();
		return et;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		menu.clear();
		View v = this.getCurrentFocus();
		if (v != null) {
			// these only make sense if we have a focused control
			if (!(v instanceof EditText)) {
				return true;
			}

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
		menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, "Refresh");
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int choice = item.getItemId();

		// delete old controls and refetch data
		if (choice == MENU_REFRESH) {
			// delete old controls
			TableLayout tbl;
			tbl = (TableLayout) findViewById(R.id.table_cool);
			tbl.removeViews(0, tbl.getChildCount());
			tbl = (TableLayout) findViewById(R.id.table_heat);
			tbl.removeViews(0, tbl.getChildCount());
			state_old = null;

			// reload mode spinner
			new FetchMode().execute("tstat", "Loading mode");

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
}
