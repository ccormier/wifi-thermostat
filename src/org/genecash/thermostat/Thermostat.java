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
	private static final int MENU_DELETE = 0;
	private static final int MENU_ADD_BEFORE = 1;
	private static final int MENU_ADD_AFTER = 2;
	private static final int MENU_EXIT = 3;

	private static final String ADDR_KEY = "addr";
	private static final String TAB_KEY = "tab";

	int oldMode;
	String addr = null;
	Spinner mode;
	TextView status;
	ActionBar actionBar = null;

	// the thermostat is single-threaded, this ensures we stick to that
	final ReentrantLock netLock = new ReentrantLock();

	Bundle state_old;
	final HashMap<String, JSONObject> state_new = new HashMap<String, JSONObject>();

	SimpleDateFormat prettyFormat = new SimpleDateFormat("hh:mm a", Locale.US);
	Calendar cal = prettyFormat.getCalendar();
	// no motherfucking clue how to get these in a localized manner. fuck it, I'll just hard code it!
	String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.thermostat);

		state_old = savedInstanceState;

		status = (TextView) findViewById(R.id.status);

		// message("Locating thermostat");
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
				message(addr.substring(1));
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

		// this should happen automatically, but nooooo....
		if ((state_old != null) && (state_old.containsKey(TAB_KEY)) && (state_old.getString(TAB_KEY).equals(tab.getText().toString()))) {
			tab.select();
		}

		// create mode spinner
		oldMode = -1;
		String[] modes = { "Off", "Heat", "Cool", "Auto" };
		mode = (Spinner) findViewById(R.id.mode);
		ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, modes);
		mode.setAdapter(modeAdapter);

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
				message("Setting mode");
				JSONObject json = new JSONObject();
				try {
					json.put("tmode", mode.getSelectedItemPosition());
				} catch (Exception e) {
					message(e.toString());
					return;
				}
				new WriteURL().execute("tstat", json.toString());
			}

			@Override
			public void onNothingSelected(AdapterView<?> arg0) {
			}
		});

		// load mode spinner
		// message("Loading mode");
		new FetchMode().execute("tstat");
	}

	// display status message
	void message(String m) {
		status.setText(m);
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
		boolean populated;

		public MyTabListener(int ctrl, int btn, int tbl, String path, String prompt) {
			mCtrl = (LinearLayout) findViewById(ctrl);
			mTbl = (TableLayout) findViewById(tbl);
			mBtn = (Button) findViewById(btn);
			mPath = path;
			mPrompt = prompt;
			populated = false;
		}

		public void onTabSelected(Tab tab, FragmentTransaction ft) {
			mCtrl.setVisibility(View.VISIBLE);
			if (!populated) {
				populated = true;
				message(mPrompt);
				new FetchProgram().execute(mPath);
			}

			// set up action for update button
			mBtn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					message("Sending program");
					updateProgram(mPath, mTbl);
				}
			});
		}

		public void onTabUnselected(Tab tab, FragmentTransaction ft) {
			mCtrl.setVisibility(View.GONE);
		}

		public void onTabReselected(Tab tab, FragmentTransaction ft) {
		}
	}

	// discover a thermostat on the network using SSDP
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
				// wait a maximum of 2 seconds
				sock.setSoTimeout(2000);
				sock.send(sendPacket);

				// receive response
				byte[] receiveData = new byte[4096];
				DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
				sock.receive(receivePacket);
				String resp = new String(receivePacket.getData());

				// unlock WiFi so it can powersave
				multicastLock.release();

				// the response looks like:
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
	class ReadURL extends AsyncTask<String, Void, Void> {
		String error = null;
		String path;
		JSONObject json = null;
		StringBuilder bagOfShit = new StringBuilder();

		@Override
		protected Void doInBackground(String... params) {
			netLock.lock();
			try {
				path = params[0];

				// check to see if we have this data already
				if ((state_old != null) && (state_old.containsKey(path))) {
					json = new JSONObject(state_old.getString(path));
					return null;
				}

				URL url = new URL("http://" + addr + "/" + path);
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();

				// this is just the shittiest way to read data since COBOL
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
			// why are Void and void different? fucking retarded!
			return null;
		}
	}

	// generic class to write stuff to thermostat
	class WriteURL extends AsyncTask<String, Void, Void> {
		String error = null;

		@Override
		protected Void doInBackground(String... params) {
			netLock.lock();
			try {
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
			// why are Void and void different? fucking retarded!
			return null;
		}

		protected void onPostExecute(Void result) {
			if (error != null) {
				message(error);
				return;
			}
			message("");
		}
	}

	// set thermostat mode spinner
	class FetchMode extends ReadURL {
		@Override
		protected void onPostExecute(Void result) {
			if (error != null) {
				message(error);
				return;
			}
			try {
				// this will set the spinner, ignore that change
				oldMode = json.getInt("tmode");
				mode.setSelection(oldMode);
			} catch (Exception e) {
				message(e.toString());
				return;
			}
			message("");
		}
	}

	// fetch the program
	class FetchProgram extends ReadURL {
		TimeButton btn;
		EditText et;
		TableRow row;
		TextView tv;
		private TableLayout tbl;

		@Override
		protected void onPostExecute(Void result) {
			if (error != null) {
				message(error);
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
					JSONArray x = json.getJSONArray(ndx + "");
					for (int i = 0; i < x.length(); i += 2) {
						// Warning: "getApplicationContext()" doesn't work here, you must use "Thermostat.this" or the
						// TimePickerDialog will take a big 'ol shit - and I don't know why!
						btn = new TimeButton(Thermostat.this);
						btn.setTime(x.getInt(i));
						btn.setText();
						row.addView(btn);

						int temp = x.getInt(i + 1);
						et = new EditText(Thermostat.this);
						et.setText(temp + "");
						et.setInputType(InputType.TYPE_CLASS_PHONE);
						row.addView(et);
					}
					tbl.addView(row);
				}
			} catch (Exception e) {
				message(e.toString());
				return;
			}
			message("");
		}
	}

	// write a new program and send it
	void updateProgram(String path, TableLayout tbl) {
		JSONObject json = new JSONObject();

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
					jarray.put(Integer.parseInt(et.getText().toString()));
				} catch (Exception e) {
					message("Error: \"" + et.getText() + "\" is not a valid temperature");
					return;
				}
			}
			try {
				json.put(i + "", jarray);
			} catch (Exception e) {
				message(e.toString());
				return;
			}
		}

		// do it to it
		state_new.put(path, json);
		new WriteURL().execute(path, json.toString());
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		menu.clear();
		if (this.getCurrentFocus() != null) {
			menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "Delete item");
			menu.add(Menu.NONE, MENU_ADD_BEFORE, Menu.NONE, "Insert item before");
			menu.add(Menu.NONE, MENU_ADD_AFTER, Menu.NONE, "Insert item after");
		}
		menu.add(Menu.NONE, MENU_EXIT, Menu.NONE, "Exit");
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == MENU_EXIT) {
			finish();
			return true;
		}

		View v = this.getCurrentFocus();
		if (v == null) {
			return true;
		}
		if (!(v instanceof EditText)) {
			return true;
		}
		TableRow row = (TableRow) v.getParent();
		int i = row.indexOfChild(v);

		TimeButton btn;
		EditText et;
		switch (item.getItemId()) {
		case MENU_DELETE:
			// delete current temperature control
			row.removeView(v);
			row.removeViewAt(i - 1);
			return true;
		case MENU_ADD_BEFORE:
			// insert a temperature control before this one
			et = new EditText(this);
			et.setInputType(InputType.TYPE_CLASS_PHONE);
			row.addView(et, i - 1);

			btn = new TimeButton(this);
			row.addView(btn, i - 1);

			return true;
		case MENU_ADD_AFTER:
			// insert a temperature control after this one
			et = new EditText(this);
			et.setInputType(InputType.TYPE_CLASS_PHONE);
			row.addView(et, i + 1);

			btn = new TimeButton(this);
			row.addView(btn, i + 1);

			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
