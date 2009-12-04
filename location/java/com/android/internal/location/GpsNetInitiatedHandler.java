/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.location;

import java.io.UnsupportedEncodingException;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

/**
 * A GPS Network-initiated Handler class used by LocationManager.
 *
 * {@hide}
 */
public class GpsNetInitiatedHandler {

    private static final String TAG = "GpsNetInitiatedHandler";

    private static final boolean DEBUG = true;
    private static final boolean VERBOSE = false;

    // NI verify activity for bringing up UI (not used yet)
    public static final String ACTION_NI_VERIFY = "android.intent.action.NETWORK_INITIATED_VERIFY";
    
    // string constants for defining data fields in NI Intent
    public static final String NI_INTENT_KEY_NOTIF_ID = "notif_id";
    public static final String NI_INTENT_KEY_TITLE = "title";
    public static final String NI_INTENT_KEY_MESSAGE = "message";
    public static final String NI_INTENT_KEY_TIMEOUT = "timeout";
    public static final String NI_INTENT_KEY_DEFAULT_RESPONSE = "default_resp";
    
    // the extra command to send NI response to GpsLocationProvider
    public static final String NI_RESPONSE_EXTRA_CMD = "send_ni_response";
    
    // the extra command parameter names in the Bundle
    public static final String NI_EXTRA_CMD_NOTIF_ID = "notif_id";
    public static final String NI_EXTRA_CMD_RESPONSE = "response";
    
    // these need to match GpsNiType constants in gps_ni.h
    public static final int GPS_NI_TYPE_VOICE = 1;
    public static final int GPS_NI_TYPE_UMTS_SUPL = 2;
    public static final int GPS_NI_TYPE_UMTS_CTRL_PLANE = 3;
    
    // these need to match GpsUserResponseType constants in gps_ni.h    
    public static final int GPS_NI_RESPONSE_ACCEPT = 1;
    public static final int GPS_NI_RESPONSE_DENY = 2;
    public static final int GPS_NI_RESPONSE_NORESP = 3;    
    
    // these need to match GpsNiNotifyFlags constants in gps_ni.h
    public static final int GPS_NI_NEED_NOTIFY = 0x0001;
    public static final int GPS_NI_NEED_VERIFY = 0x0002;
    public static final int GPS_NI_PRIVACY_OVERRIDE = 0x0004;
    
    // these need to match GpsNiEncodingType in gps_ni.h
    public static final int GPS_ENC_NONE = 0;
    public static final int GPS_ENC_SUPL_GSM_DEFAULT = 1;
    public static final int GPS_ENC_SUPL_UTF8 = 2;
    public static final int GPS_ENC_SUPL_UCS2 = 3;
    public static final int GPS_ENC_UNKNOWN = -1;
    
    private final Context mContext;
    
    // parent gps location provider
    private final GpsLocationProvider mGpsLocationProvider;
    
    // configuration of notificaiton behavior
    private boolean mPlaySounds = false;
    private boolean visible = true;
    private boolean mPopupImmediately = true;
    
    // Set to true if string from HAL is encoded as Hex, e.g., "3F0039"    
    static private boolean mIsHexInput = true;
        
    public static class GpsNiNotification
    {
    	int notificationId;
    	int niType;
    	boolean needNotify;
    	boolean needVerify;
    	boolean privacyOverride;
    	int timeout;
    	int defaultResponse;
    	String requestorId;
    	String text;
    	int requestorIdEncoding;
    	int textEncoding;
    	Bundle extras;
    };
    
    public static class GpsNiResponse {
    	/* User reponse, one of the values in GpsUserResponseType */
    	int userResponse;
    	/* Optional extra data to pass with the user response */
    	Bundle extras;
    };
    
    /**
     * The notification that is shown when a network-initiated notification
     * (and verification) event is received. 
     * <p>
     * This is lazily created, so use {@link #setNINotification()}.
     */
    private Notification mNiNotification;
    
    public GpsNetInitiatedHandler(Context context, GpsLocationProvider gpsLocationProvider) {
    	mContext = context;       
    	mGpsLocationProvider = gpsLocationProvider;
    }
    
    // Handles NI events from HAL
    public void handleNiNotification(GpsNiNotification notif)
    {
    	if (DEBUG) Log.d(TAG, "handleNiNotification" + " notificationId: " + notif.notificationId 
    			+ " requestorId: " + notif.requestorId + " text: " + notif.text);
    	
    	// Notify and verify with immediate pop-up
    	if (notif.needNotify && notif.needVerify && mPopupImmediately)
    	{
    		// Popup the dialog box now
    		openNiDialog(notif);
    	}
    	
    	// Notify only, or delayed pop-up (change mPopupImmediately to FALSE) 
    	if (notif.needNotify && !notif.needVerify ||
    		notif.needNotify && notif.needVerify && !mPopupImmediately) 
    	{
    		// Show the notification

    		// if mPopupImmediately == FALSE and needVerify == TRUE, a dialog will be opened
    		// when the user opens the notification message
    		
    		setNiNotification(notif);
    	}
    	
    	// ACCEPT cases: 1. Notify, no verify; 2. no notify, no verify; 3. privacy override.
    	if ( notif.needNotify && !notif.needVerify || 
    		!notif.needNotify && !notif.needVerify || 
    		 notif.privacyOverride)
    	{
    		try {
    			mGpsLocationProvider.getNetInitiatedListener().sendNiResponse(notif.notificationId, GPS_NI_RESPONSE_ACCEPT);
    		} 
    		catch (RemoteException e)
    		{
    			Log.e(TAG, e.getMessage());
    		}
    	}
    	
    	//////////////////////////////////////////////////////////////////////////
    	//   A note about timeout
    	//   According to the protocol, in the need_notify and need_verify case,
    	//   a default response should be sent when time out.
    	//   
    	//   In some GPS hardware, the GPS driver (under HAL) can handle the timeout case
    	//   and this class GpsNetInitiatedHandler does not need to do anything.
    	//   
    	//   However, the UI should at least close the dialog when timeout. Further, 
    	//   for more general handling, timeout response should be added to the Handler here.
    	//    	    	
    }
    
    // Sets the NI notification.
    private synchronized void setNiNotification(GpsNiNotification notif) {
        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }
      
    	String title = getNotifTitle(notif);
    	String message = getNotifMessage(notif);
        
        if (DEBUG) Log.d(TAG, "setNiNotification, notifyId: " + notif.notificationId +
        		", title: " + title +
        		", message: " + message);
        
    	// Construct Notification
    	if (mNiNotification == null) {
        	mNiNotification = new Notification();
        	mNiNotification.icon = com.android.internal.R.drawable.stat_sys_gps_on; /* Change notification icon here */
        	mNiNotification.when = 0;
        }
    	
        if (mPlaySounds) {
        	mNiNotification.defaults |= Notification.DEFAULT_SOUND;
        } else {
        	mNiNotification.defaults &= ~Notification.DEFAULT_SOUND;
        }        
        
        mNiNotification.flags = Notification.FLAG_ONGOING_EVENT;
        mNiNotification.tickerText = getNotifTicker(notif);
        
        // if not to popup dialog immediately, pending intent will open the dialog
        Intent intent = !mPopupImmediately ? getDlgIntent(notif) : new Intent();    	        
        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, intent, 0);                
        mNiNotification.setLatestEventInfo(mContext, title, message, pi);
        
        if (visible) {
            notificationManager.notify(notif.notificationId, mNiNotification);
        } else {
            notificationManager.cancel(notif.notificationId);
        }
    }
    
    // Opens the notification dialog and waits for user input
    private void openNiDialog(GpsNiNotification notif) 
    {
    	Intent intent = getDlgIntent(notif);
    	
    	if (DEBUG) Log.d(TAG, "openNiDialog, notifyId: " + notif.notificationId + 
    			", requestorId: " + notif.requestorId + 
    			", text: " + notif.text);               	

    	mContext.startActivity(intent);
    }
    
    // Construct the intent for bringing up the dialog activity, which shows the 
    // notification and takes user input
    private Intent getDlgIntent(GpsNiNotification notif)
    {
    	Intent intent = new Intent();
    	String title = getDialogTitle(notif);
    	String message = getDialogMessage(notif);
    	
    	// directly bring up the NI activity
    	intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    	intent.setClass(mContext, com.android.internal.app.NetInitiatedActivity.class);    	

    	// put data in the intent
    	intent.putExtra(NI_INTENT_KEY_NOTIF_ID, notif.notificationId);    	
    	intent.putExtra(NI_INTENT_KEY_TITLE, title);
    	intent.putExtra(NI_INTENT_KEY_MESSAGE, message);
    	intent.putExtra(NI_INTENT_KEY_TIMEOUT, notif.timeout);
    	intent.putExtra(NI_INTENT_KEY_DEFAULT_RESPONSE, notif.defaultResponse);
    	
    	if (DEBUG) Log.d(TAG, "generateIntent, title: " + title + ", message: " + message +
    			", timeout: " + notif.timeout);
    	
    	return intent;
    }
    
    // Converts a string (or Hex string) to a char array
    static byte[] stringToByteArray(String original, boolean isHex)
    {
    	int length = isHex ? original.length() / 2 : original.length();
    	byte[] output = new byte[length];
    	int i;
    	
    	if (isHex)
    	{
    		for (i = 0; i < length; i++)
    		{
    			output[i] = (byte) Integer.parseInt(original.substring(i*2, i*2+2), 16);
    		}
    	}
    	else {
    		for (i = 0; i < length; i++)
    		{
    			output[i] = (byte) original.charAt(i);
    		}
    	}
    	
    	return output;
    }
    
    /**
     * Unpacks an byte array containing 7-bit packed characters into a String.
     * 
     * @param input a 7-bit packed char array
     * @return the unpacked String
     */
    static String decodeGSMPackedString(byte[] input)
    {
    	final char CHAR_CR = 0x0D;
    	int nStridx = 0;
    	int nPckidx = 0;
    	int num_bytes = input.length;
    	int cPrev = 0;
    	int cCurr = 0;
    	byte nShift;
    	byte nextChar;
    	byte[] stringBuf = new byte[input.length * 2]; 
    	String result = "";
    	
    	while(nPckidx < num_bytes)
    	{
    		nShift = (byte) (nStridx & 0x07);
    		cCurr = input[nPckidx++];
    		if (cCurr < 0) cCurr += 256;

    		/* A 7-bit character can be split at the most between two bytes of packed
    		 ** data.
    		 */
    		nextChar = (byte) (( (cCurr << nShift) | (cPrev >> (8-nShift)) ) & 0x7F);
    		stringBuf[nStridx++] = nextChar;

    		/* Special case where the whole of the next 7-bit character fits inside
    		 ** the current byte of packed data.
    		 */
    		if(nShift == 6)
    		{
    			/* If the next 7-bit character is a CR (0x0D) and it is the last
    			 ** character, then it indicates a padding character. Drop it.
    			 */
                        if (nPckidx == num_bytes && (cCurr >> 1) == CHAR_CR)
    			{
    				break;
    			}
    			
    			nextChar = (byte) (cCurr >> 1); 
    			stringBuf[nStridx++] = nextChar;
    		}

    		cPrev = cCurr;
    	}
    	
    	try{
    		result = new String(stringBuf, 0, nStridx, "US-ASCII");
    	}
    	catch (UnsupportedEncodingException e)
    	{
    		Log.e(TAG, e.getMessage());
    	}
    	
    	return result;
    }
    
    static String decodeUTF8String(byte[] input)
    {
    	String decoded = "";
    	try {
    		decoded = new String(input, "UTF-8");
    	}
    	catch (UnsupportedEncodingException e)
    	{ 
    		Log.e(TAG, e.getMessage());
    	} 
		return decoded;
    }
    
    static String decodeUCS2String(byte[] input)
    {
    	String decoded = "";
    	try {
    		decoded = new String(input, "UTF-16");
    	}
    	catch (UnsupportedEncodingException e)
    	{ 
    		Log.e(TAG, e.getMessage());
    	} 
		return decoded;
    }
    
    /** Decode NI string
     * 
     * @param original   The text string to be decoded
     * @param isHex      Specifies whether the content of the string has been encoded as a Hex string. Encoding
     *                   a string as Hex can allow zeros inside the coded text. 
     * @param coding     Specifies the coding scheme of the string, such as GSM, UTF8, UCS2, etc. This coding scheme
     * 					 needs to match those used passed to HAL from the native GPS driver. Decoding is done according
     *                   to the <code> coding </code>, after a Hex string is decoded. Generally, if the
     *                   notification strings don't need further decoding, <code> coding </code> encoding can be 
     *                   set to -1, and <code> isHex </code> can be false.
     * @return the decoded string
     */
    static private String decodeString(String original, boolean isHex, int coding)
    {
    	String decoded = original;
    	byte[] input = stringToByteArray(original, isHex);

    	switch (coding) {
    	case GPS_ENC_NONE:
    		decoded = original;
    		break;
    		
    	case GPS_ENC_SUPL_GSM_DEFAULT:
    		decoded = decodeGSMPackedString(input);
    		break;
    		
    	case GPS_ENC_SUPL_UTF8:
    		decoded = decodeUTF8String(input);
    		break;
    		
    	case GPS_ENC_SUPL_UCS2:
    		decoded = decodeUCS2String(input);
    		break;
    		
    	case GPS_ENC_UNKNOWN:
    		decoded = original;
    		break;
    		
    	default:
    		Log.e(TAG, "Unknown encoding " + coding + " for NI text " + original);
    		break;
    	}
    	return decoded;
    }
    
    // change this to configure notification display
    static private String getNotifTicker(GpsNiNotification notif)
    {
    	String ticker = String.format("Position request! ReqId: [%s] ClientName: [%s]",
    			decodeString(notif.requestorId, mIsHexInput, notif.requestorIdEncoding),
    			decodeString(notif.text, mIsHexInput, notif.textEncoding));
    	return ticker;
    }
    
    // change this to configure notification display
    static private String getNotifTitle(GpsNiNotification notif)
    {
    	String title = String.format("Position Request");
    	return title;
    }
    
    // change this to configure notification display
    static private String getNotifMessage(GpsNiNotification notif)
    {
    	String message = String.format(
    			"NI Request received from [%s] for client [%s]!", 
    			decodeString(notif.requestorId, mIsHexInput, notif.requestorIdEncoding),
    			decodeString(notif.text, mIsHexInput, notif.textEncoding));
    	return message;
    }       
    
    // change this to configure dialog display (for verification)
    static public String getDialogTitle(GpsNiNotification notif)
    {
    	return getNotifTitle(notif);
    }
    
    // change this to configure dialog display (for verification)
    static private String getDialogMessage(GpsNiNotification notif)
    {
    	return getNotifMessage(notif);
    }
    
}
