/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.server.wifi.util.CertificateSubjectInfo;
import com.android.server.wifi.util.NativeUtil;
import com.android.wifi.resources.R;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** This class is used to handle insecure EAP networks. */
public class InsecureEapNetworkHandler {
    private static final String TAG = "InsecureEapNetworkHandler";

    @VisibleForTesting
    static final String ACTION_CERT_NOTIF_TAP =
            "com.android.server.wifi.ClientModeImpl.ACTION_CERT_NOTIF_TAP";
    @VisibleForTesting
    static final String ACTION_CERT_NOTIF_ACCEPT =
            "com.android.server.wifi.ClientModeImpl.ACTION_CERT_NOTIF_ACCEPT";
    @VisibleForTesting
    static final String ACTION_CERT_NOTIF_REJECT =
            "com.android.server.wifi.ClientModeImpl.ACTION_CERT_NOTIF_REJECT";
    @VisibleForTesting
    static final String EXTRA_PENDING_CERT_SSID =
            "com.android.server.wifi.ClientModeImpl.EXTRA_PENDING_CERT_SSID";

    @VisibleForTesting
    static final String NOTIFICATION_WAITING_TIMER_TAG =
            "InsecureEapNetworkHandler Notification Waiting";
    // If a notification is not responded in time, disconnect from the network
    // to allow connecting to other networks.
    @VisibleForTesting
    static final long NOTIFICATION_WAITING_TIME_MS = 10 * 1000;

    private final String mCaCertHelpLink;
    private final WifiContext mContext;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiNative mWifiNative;
    private final FrameworkFacade mFacade;
    private final WifiNotificationManager mNotificationManager;
    private final WifiDialogManager mWifiDialogManager;
    private final boolean mIsTrustOnFirstUseSupported;
    private final boolean mIsInsecureEnterpriseConfigurationAllowed;
    private final InsecureEapNetworkHandlerCallbacks mCallbacks;
    private final String mInterfaceName;
    private final Handler mHandler;
    private final Clock mClock;
    private final AlarmManager mAlarmManager;
    private final AlarmManager.OnAlarmListener mNotificationWaitingTimerListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    if (null == mCurrentTofuConfig) return;
                    // Disconnect this network to avoid staying at connecting state.
                    // Once the user accepts this via notification, this network could
                    // be auto-connected.
                    mWifiConfigManager.allowAutojoin(mCurrentTofuConfig.networkId, false);

                    // If the connecting network is changed, do not need to disconnect
                    // the network anymore.
                    if (null == mConnectingConfig) return;
                    if (mConnectingConfig.networkId != mCurrentTofuConfig.networkId) return;
                    mWifiNative.disconnect(mInterfaceName);
                }
            };

    // The latest connecting configuration from the caller, it is updated on calling
    // prepareConnection() always. This is used to ensure that current TOFU config is aligned
    // with the caller connecting config.
    @NonNull
    private WifiConfiguration mConnectingConfig = null;
    // The connecting configuration which is a valid TOFU configuration, it is updated
    // only when the connecting configuration is a valid TOFU configuration and used
    // by later TOFU procedure.
    @NonNull
    private WifiConfiguration mCurrentTofuConfig = null;
    private int mPendingCaCertDepth = -1;
    @Nullable
    private X509Certificate mPendingCaCert = null;
    @Nullable
    private X509Certificate mPendingServerCert = null;
    // This is updated on setting a pending CA cert.
    private CertificateSubjectInfo mPendingCaCertSubjectInfo = null;
    // This is updated on setting a pending CA cert.
    private CertificateSubjectInfo mPendingCaCertIssuerInfo = null;
    // Record the whole server cert chain from Root CA to the server cert.
    private List<X509Certificate> mServerCertChain = new ArrayList<>();
    private WifiDialogManager.DialogHandle mTofuAlertDialog = null;
    private boolean mIsCertNotificationReceiverRegistered = false;

    BroadcastReceiver mCertNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String ssid = intent.getStringExtra(EXTRA_PENDING_CERT_SSID);
            // This is an onGoing notification, dismiss it once an action is sent.
            dismissDialogAndNotification();
            Log.d(TAG, "Received CertNotification: ssid=" + ssid + ", action=" + action);
            if (TextUtils.equals(action, ACTION_CERT_NOTIF_TAP)) {
                askForUserApprovalForCaCertificate();
            } else if (TextUtils.equals(action, ACTION_CERT_NOTIF_ACCEPT)) {
                handleAccept(ssid);
            } else if (TextUtils.equals(action, ACTION_CERT_NOTIF_REJECT)) {
                handleReject(ssid);
            }
        }
    };

    public InsecureEapNetworkHandler(@NonNull WifiContext context,
            @NonNull WifiConfigManager wifiConfigManager,
            @NonNull WifiNative wifiNative,
            @NonNull FrameworkFacade facade,
            @NonNull WifiNotificationManager notificationManager,
            @NonNull WifiDialogManager wifiDialogManager,
            @NonNull Clock clock,
            @NonNull AlarmManager alarmManager,
            boolean isTrustOnFirstUseSupported,
            boolean isInsecureEnterpriseConfigurationAllowed,
            @NonNull InsecureEapNetworkHandlerCallbacks callbacks,
            @NonNull String interfaceName,
            @NonNull Handler handler) {
        mContext = context;
        mWifiConfigManager = wifiConfigManager;
        mWifiNative = wifiNative;
        mFacade = facade;
        mNotificationManager = notificationManager;
        mWifiDialogManager = wifiDialogManager;
        mClock = clock;
        mAlarmManager = alarmManager;
        mIsTrustOnFirstUseSupported = isTrustOnFirstUseSupported;
        mIsInsecureEnterpriseConfigurationAllowed = isInsecureEnterpriseConfigurationAllowed;
        mCallbacks = callbacks;
        mInterfaceName = interfaceName;
        mHandler = handler;

        mCaCertHelpLink = mContext.getString(R.string.config_wifiCertInstallationHelpLink);
    }

    /**
     * Prepare data for a new connection.
     *
     * Prepare data if this is an Enterprise configuration, which
     * uses Server Cert, without a valid Root CA certificate or user approval.
     *
     * @param config the running wifi configuration.
     */
    public void prepareConnection(@NonNull WifiConfiguration config) {
        if (null == config) return;
        mConnectingConfig = config;

        if (!config.isEnterprise()) return;
        WifiEnterpriseConfig entConfig = config.enterpriseConfig;
        if (!entConfig.isEapMethodServerCertUsed()) return;
        if (entConfig.hasCaCertificate()) return;

        Log.d(TAG, "prepareConnection: isTofuSupported=" + mIsTrustOnFirstUseSupported
                + ", isInsecureEapNetworkAllowed=" + mIsInsecureEnterpriseConfigurationAllowed
                + ", isTofuEnabled=" + entConfig.isTrustOnFirstUseEnabled()
                + ", isUserApprovedNoCaCert=" + entConfig.isUserApproveNoCaCert());
        // If TOFU is not supported or insecure EAP network is allowed without TOFU enabled,
        // return to skip the dialog if this network is approved before.
        if (entConfig.isUserApproveNoCaCert()) {
            if (!mIsTrustOnFirstUseSupported) return;
            if (mIsInsecureEnterpriseConfigurationAllowed
                    && !entConfig.isTrustOnFirstUseEnabled()) {
                return;
            }
        }

        mCurrentTofuConfig = config;
        dismissDialogAndNotification();
        registerCertificateNotificationReceiver();
        // Remove cached PMK in the framework and supplicant to avoid
        // skipping the EAP flow.
        clearNativeData();
        Log.d(TAG, "Remove native cached data and networks for TOFU.");
    }

    /**
     * Store the received certifiate for later use.
     *
     * @param ssid the target network SSID.
     * @param depth the depth of this cert. The Root CA should be 0 or
     *        a positive number, and the server cert is 0.
     * @param cert the Root CA certificate from the server.
     * @return true if the cert is cached; otherwise, false.
     */
    public boolean setPendingCertificate(@NonNull String ssid, int depth,
            @NonNull X509Certificate cert) {
        String configProfileKey = mCurrentTofuConfig != null
                ? mCurrentTofuConfig.getProfileKey() : "null";
        Log.d(TAG, "setPendingCertificate: " + "ssid=" + ssid + " depth=" + depth
                + " current config=" + configProfileKey);
        if (TextUtils.isEmpty(ssid)) return false;
        if (null == mCurrentTofuConfig) return false;
        if (!TextUtils.equals(ssid, mCurrentTofuConfig.SSID)) return false;
        if (null == cert) return false;
        if (depth < 0) return false;

        if (!mServerCertChain.contains(cert)) {
            mServerCertChain.add(cert);
        }

        // 0 is the tail, i.e. the server cert.
        if (depth == 0 && null == mPendingServerCert) {
            mPendingServerCert = cert;
            Log.d(TAG, "Pending server certificate: " + mPendingServerCert);
        }
        if (depth < mPendingCaCertDepth) {
            Log.d(TAG, "Ignore intermediate cert." + cert);
            return true;
        }

        mPendingCaCertSubjectInfo = CertificateSubjectInfo.parse(
                cert.getSubjectDN().getName());
        if (null == mPendingCaCertSubjectInfo) {
            Log.e(TAG, "CA cert has no valid subject.");
            return false;
        }
        mPendingCaCertIssuerInfo = CertificateSubjectInfo.parse(
                cert.getIssuerDN().getName());
        if (null == mPendingCaCertIssuerInfo) {
            Log.e(TAG, "CA cert has no valid issuer.");
            return false;
        }
        mPendingCaCertDepth = depth;
        mPendingCaCert = cert;
        Log.d(TAG, "Pending Root CA certificate: " + mPendingCaCert);
        return true;
    }

    /**
     * Ask for the user approval if necessary.
     *
     * For TOFU is supported and an EAP network without a CA certificate.
     * - if insecure EAP networks are not allowed
     *    - if TOFU is not enabled, disconnect it.
     *    - if no pending CA cert, disconnect it.
     *    - if no server cert, disconnect it.
     * - if insecure EAP networks are allowed and TOFU is not enabled
     *    - follow no TOFU support flow.
     * - if TOFU is enabled, CA cert is pending, and server cert is pending
     *     - gate the connecitvity event here
     *     - if this request is from a user, launch a dialog to get the user approval.
     *     - if this request is from auto-connect, launch a notification.
     * If TOFU is not supported, the confirmation flow is similar. Instead of installing CA
     * cert from the server, just mark this network is approved by the user.
     *
     * @param isUserSelected indicates that this connection is triggered by a user.
     * @return true if the user approval is needed; otherwise, false.
     */
    public boolean startUserApprovalIfNecessary(boolean isUserSelected) {
        if (null == mConnectingConfig || null == mCurrentTofuConfig) return false;
        if (mConnectingConfig.networkId != mCurrentTofuConfig.networkId) return false;

        // If Trust On First Use is supported and insecure enterprise configuration
        // is not allowed, TOFU must be used for an Enterprise network without certs.
        if (mIsTrustOnFirstUseSupported && !mIsInsecureEnterpriseConfigurationAllowed
                && !mCurrentTofuConfig.enterpriseConfig.isTrustOnFirstUseEnabled()) {
            Log.d(TAG, "Trust On First Use is not enabled.");
            handleError(mCurrentTofuConfig.SSID);
            return true;
        }

        if (useTrustOnFirstUse()) {
            if (null == mPendingCaCert) {
                Log.d(TAG, "No valid CA cert for TLS-based connection.");
                handleError(mCurrentTofuConfig.SSID);
                return true;
            } else if (null == mPendingServerCert) {
                Log.d(TAG, "No valid Server cert for TLS-based connection.");
                handleError(mCurrentTofuConfig.SSID);
                return true;
            } else if (!isServerCertChainValid()) {
                Log.d(TAG, "Server cert chain is invalid.");
                String title = mContext.getString(R.string.wifi_tofu_invalid_cert_chain_title,
                        mCurrentTofuConfig.SSID);
                String message = mContext.getString(R.string.wifi_tofu_invalid_cert_chain_message);
                String okButtonText = mContext.getString(
                        R.string.wifi_tofu_invalid_cert_chain_ok_text);

                handleError(mCurrentTofuConfig.SSID);

                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(message)) return true;

                if (isUserSelected) {
                    mTofuAlertDialog = mWifiDialogManager.createLegacySimpleDialog(
                        title,
                        message,
                        null /* positiveButtonText */,
                        null /* negativeButtonText */,
                        okButtonText,
                        new WifiDialogManager.SimpleDialogCallback() {
                            @Override
                            public void onPositiveButtonClicked() {
                                // Not used.
                            }

                            @Override
                            public void onNegativeButtonClicked() {
                                // Not used.
                            }

                            @Override
                            public void onNeutralButtonClicked() {
                                // Not used.
                            }

                            @Override
                            public void onCancelled() {
                                // Not used.
                            }
                        },
                        new WifiThreadRunner(mHandler));
                    mTofuAlertDialog.launchDialog();
                } else {
                    Notification.Builder builder = mFacade.makeNotificationBuilder(mContext,
                            WifiService.NOTIFICATION_NETWORK_ALERTS)
                            .setSmallIcon(
                                    Icon.createWithResource(mContext.getWifiOverlayApkPkgName(),
                                    com.android.wifi.resources.R
                                            .drawable.stat_notify_wifi_in_range))
                            .setContentTitle(title)
                            .setContentText(message)
                            .setStyle(new Notification.BigTextStyle().bigText(message))
                            .setColor(mContext.getResources().getColor(
                                        android.R.color.system_notification_accent_color));
                    mNotificationManager.notify(SystemMessage.NOTE_SERVER_CA_CERTIFICATE,
                            builder.build());
                }
                return true;
            }
        } else if (mIsInsecureEnterpriseConfigurationAllowed) {
            Log.i(TAG, "networks without the server cert are allowed, skip it.");
            return false;
        }

        Log.d(TAG, "startUserApprovalIfNecessaryForInsecureEapNetwork: mIsUserSelected="
                + isUserSelected);

        if (isUserSelected) {
            askForUserApprovalForCaCertificate();
        } else {
            notifyUserForCaCertificate();
        }
        return true;
    }

    private boolean isServerCertChainValid() {
        if (mServerCertChain.size() == 0) return false;

        X509Certificate parentCert = null;
        for (X509Certificate cert: mServerCertChain) {
            String subject = cert.getSubjectDN().getName();
            String issuer = cert.getIssuerDN().getName();
            boolean isCa = cert.getBasicConstraints() > 0;
            Log.d(TAG, "Subject: " + subject + ", Issuer: " + issuer + ", isCA: " + isCa);

            if (parentCert == null) {
                // The root cert, it should be a CA cert or a self-signed cert.
                if (!isCa && !subject.equals(issuer)) {
                    Log.e(TAG, "The root cert is not a CA cert or a self-signed cert.");
                    return false;
                }
            } else {
                // The issuer of intermediate cert of the leaf cert should be
                // the same as the subject of its parent cert.
                if (!parentCert.getSubjectDN().getName().equals(issuer)) {
                    Log.e(TAG, "The issuer does not match the subject of its parent.");
                    return false;
                }
            }
            parentCert = cert;
        }
        return true;
    }

    private boolean useTrustOnFirstUse() {
        return mIsTrustOnFirstUseSupported
                && mCurrentTofuConfig.enterpriseConfig.isTrustOnFirstUseEnabled();
    }

    private void registerCertificateNotificationReceiver() {
        unregisterCertificateNotificationReceiver();

        IntentFilter filter = new IntentFilter();
        if (useTrustOnFirstUse()) {
            filter.addAction(ACTION_CERT_NOTIF_TAP);
        } else {
            filter.addAction(ACTION_CERT_NOTIF_ACCEPT);
            filter.addAction(ACTION_CERT_NOTIF_REJECT);
        }
        mContext.registerReceiver(mCertNotificationReceiver, filter, null, mHandler);
        mIsCertNotificationReceiverRegistered = true;
    }

    private void unregisterCertificateNotificationReceiver() {
        if (!mIsCertNotificationReceiverRegistered) return;

        mContext.unregisterReceiver(mCertNotificationReceiver);
        mIsCertNotificationReceiverRegistered = false;
    }

    @VisibleForTesting
    void handleAccept(@NonNull String ssid) {
        if (!isConnectionValid(ssid)) return;

        if (!useTrustOnFirstUse()) {
            mWifiConfigManager.setUserApproveNoCaCert(mCurrentTofuConfig.networkId, true);
        } else {
            if (null == mPendingCaCert || null == mPendingServerCert) {
                handleError(ssid);
                return;
            }
            if (!mWifiConfigManager.updateCaCertificate(
                    mCurrentTofuConfig.networkId, mPendingCaCert, mPendingServerCert)) {
                // The user approved this network,
                // keep the connection regardless of the result.
                Log.e(TAG, "Cannot update CA cert to network " + mCurrentTofuConfig.getProfileKey()
                        + ", CA cert = " + mPendingCaCert);
            }
        }
        mWifiConfigManager.allowAutojoin(mCurrentTofuConfig.networkId, true);
        dismissDialogAndNotification();
        clearInternalData();

        if (null != mCallbacks) mCallbacks.onAccept(ssid);
    }

    @VisibleForTesting
    void handleReject(@NonNull String ssid) {
        if (!isConnectionValid(ssid)) return;

        mWifiConfigManager.allowAutojoin(mCurrentTofuConfig.networkId, false);
        dismissDialogAndNotification();
        clearInternalData();
        clearNativeData();

        if (null != mCallbacks) mCallbacks.onReject(ssid);
    }

    private void handleError(@Nullable String ssid) {
        if (mCurrentTofuConfig != null) {
            mWifiConfigManager.allowAutojoin(mCurrentTofuConfig.networkId, false);
        }
        dismissDialogAndNotification();
        clearInternalData();
        clearNativeData();

        if (null != mCallbacks) mCallbacks.onError(ssid);
    }

    private void askForUserApprovalForCaCertificate() {
        if (mCurrentTofuConfig == null || TextUtils.isEmpty(mCurrentTofuConfig.SSID)) return;
        if (useTrustOnFirstUse()) {
            if (null == mPendingCaCert || null == mPendingServerCert) {
                Log.e(TAG, "Cannot launch a dialog for TOFU without "
                        + "a valid pending CA certificate.");
                return;
            }
        }
        dismissDialogAndNotification();

        String title = useTrustOnFirstUse()
                ? mContext.getString(R.string.wifi_ca_cert_dialog_title)
                : mContext.getString(R.string.wifi_ca_cert_dialog_preT_title);
        String positiveButtonText = useTrustOnFirstUse()
                ? mContext.getString(R.string.wifi_ca_cert_dialog_continue_text)
                : mContext.getString(R.string.wifi_ca_cert_dialog_preT_continue_text);
        String negativeButtonText = useTrustOnFirstUse()
                ? mContext.getString(R.string.wifi_ca_cert_dialog_abort_text)
                : mContext.getString(R.string.wifi_ca_cert_dialog_preT_abort_text);

        String message = null;
        String messageUrl = null;
        int messageUrlStart = 0;
        int messageUrlEnd = 0;
        if (useTrustOnFirstUse()) {
            String signature = NativeUtil.hexStringFromByteArray(
                    mPendingCaCert.getSignature());
            StringBuilder contentBuilder = new StringBuilder()
                    .append(mContext.getString(R.string.wifi_ca_cert_dialog_message_hint))
                    .append(mContext.getString(
                            R.string.wifi_ca_cert_dialog_message_server_name_text,
                            mPendingCaCertSubjectInfo.commonName))
                    .append(mContext.getString(
                            R.string.wifi_ca_cert_dialog_message_issuer_name_text,
                            mPendingCaCertIssuerInfo.commonName));
            if (!TextUtils.isEmpty(mPendingCaCertSubjectInfo.organization)) {
                contentBuilder.append(mContext.getString(
                        R.string.wifi_ca_cert_dialog_message_organization_text,
                        mPendingCaCertSubjectInfo.organization));
            }
            if (!TextUtils.isEmpty(mPendingCaCertSubjectInfo.email)) {
                contentBuilder.append(mContext.getString(
                        R.string.wifi_ca_cert_dialog_message_contact_text,
                        mPendingCaCertSubjectInfo.email));
            }
            contentBuilder
                    .append(mContext.getString(
                            R.string.wifi_ca_cert_dialog_message_signature_name_text,
                            signature.substring(0, 16)));
            message = contentBuilder.toString();
        } else {
            String hint = mContext.getString(
                    R.string.wifi_ca_cert_dialog_preT_message_hint, mCurrentTofuConfig.SSID);
            String linkText = mContext.getString(
                    R.string.wifi_ca_cert_dialog_preT_message_link);
            message = hint + " " + linkText;
            messageUrl = mCaCertHelpLink;
            messageUrlStart = hint.length() + 1;
            messageUrlEnd = message.length();
        }
        mTofuAlertDialog = mWifiDialogManager.createLegacySimpleDialogWithUrl(
                title,
                message,
                messageUrl,
                messageUrlStart,
                messageUrlEnd,
                positiveButtonText,
                negativeButtonText,
                null /* neutralButtonText */,
                new WifiDialogManager.SimpleDialogCallback() {
                    @Override
                    public void onPositiveButtonClicked() {
                        if (mCurrentTofuConfig == null) {
                            return;
                        }
                        handleAccept(mCurrentTofuConfig.SSID);
                    }

                    @Override
                    public void onNegativeButtonClicked() {
                        if (mCurrentTofuConfig == null) {
                            return;
                        }
                        handleReject(mCurrentTofuConfig.SSID);
                    }

                    @Override
                    public void onNeutralButtonClicked() {
                        // Not used.
                        if (mCurrentTofuConfig == null) {
                            return;
                        }
                        handleReject(mCurrentTofuConfig.SSID);
                    }

                    @Override
                    public void onCancelled() {
                        if (mCurrentTofuConfig == null) {
                            return;
                        }
                        handleReject(mCurrentTofuConfig.SSID);
                    }
                },
                new WifiThreadRunner(mHandler));
        mTofuAlertDialog.launchDialog();
    }

    private PendingIntent genCaCertNotifIntent(
            @NonNull String action, @NonNull String ssid) {
        Intent intent = new Intent(action)
                .setPackage(mContext.getServiceWifiPackageName())
                .putExtra(EXTRA_PENDING_CERT_SSID, ssid);
        return mFacade.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void notifyUserForCaCertificate() {
        if (mCurrentTofuConfig == null) return;
        if (useTrustOnFirstUse()) {
            if (null == mPendingCaCert) return;
            if (null == mPendingServerCert) return;
        }
        dismissDialogAndNotification();

        PendingIntent tapPendingIntent;
        if (useTrustOnFirstUse()) {
            tapPendingIntent = genCaCertNotifIntent(ACTION_CERT_NOTIF_TAP, mCurrentTofuConfig.SSID);
        } else {
            Intent openLinkIntent = new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse(mCaCertHelpLink))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tapPendingIntent = mFacade.getActivity(mContext, 0, openLinkIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        String title = useTrustOnFirstUse()
                ? mContext.getString(R.string.wifi_ca_cert_notification_title)
                : mContext.getString(R.string.wifi_ca_cert_notification_preT_title);
        String content = useTrustOnFirstUse()
                ? mContext.getString(R.string.wifi_ca_cert_notification_message,
                        mCurrentTofuConfig.SSID)
                : mContext.getString(R.string.wifi_ca_cert_notification_preT_message,
                        mCurrentTofuConfig.SSID);
        Notification.Builder builder = mFacade.makeNotificationBuilder(mContext,
                WifiService.NOTIFICATION_NETWORK_ALERTS)
                .setSmallIcon(Icon.createWithResource(mContext.getWifiOverlayApkPkgName(),
                            com.android.wifi.resources.R.drawable.stat_notify_wifi_in_range))
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setContentIntent(tapPendingIntent)
                .setOngoing(true)
                .setColor(mContext.getResources().getColor(
                            android.R.color.system_notification_accent_color));
        // On a device which does not support Trust On First Use,
        // a user can accept or reject this network via the notification.
        if (!useTrustOnFirstUse()) {
            Notification.Action acceptAction = new Notification.Action.Builder(
                    null /* icon */,
                    mContext.getString(R.string.wifi_ca_cert_dialog_preT_continue_text),
                    genCaCertNotifIntent(ACTION_CERT_NOTIF_ACCEPT, mCurrentTofuConfig.SSID))
                    .build();
            Notification.Action rejectAction = new Notification.Action.Builder(
                    null /* icon */,
                    mContext.getString(R.string.wifi_ca_cert_dialog_preT_abort_text),
                    genCaCertNotifIntent(ACTION_CERT_NOTIF_REJECT, mCurrentTofuConfig.SSID))
                    .build();
            builder.addAction(rejectAction).addAction(acceptAction);
        }
        mNotificationManager.notify(SystemMessage.NOTE_SERVER_CA_CERTIFICATE, builder.build());

        mAlarmManager.cancel(mNotificationWaitingTimerListener);
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mClock.getElapsedSinceBootMillis() + NOTIFICATION_WAITING_TIME_MS,
                NOTIFICATION_WAITING_TIMER_TAG, mNotificationWaitingTimerListener, mHandler);
    }

    private void dismissDialogAndNotification() {
        mNotificationManager.cancel(SystemMessage.NOTE_SERVER_CA_CERTIFICATE);
        if (mTofuAlertDialog != null) {
            mTofuAlertDialog.dismissDialog();
            mTofuAlertDialog = null;
        }
        mAlarmManager.cancel(mNotificationWaitingTimerListener);
    }

    private void clearInternalData() {
        mPendingCaCertDepth = -1;
        mPendingCaCert = null;
        mPendingServerCert = null;
        mPendingCaCertSubjectInfo = null;
        mPendingCaCertIssuerInfo = null;
        mCurrentTofuConfig = null;
    }

    private void clearNativeData() {
        // PMK should be cleared or it would skip EAP flow next time.
        if (null != mCurrentTofuConfig) {
            mWifiNative.removeNetworkCachedData(mCurrentTofuConfig.networkId);
        }
        // remove network so that supplicant's PMKSA cache is cleared
        mWifiNative.removeAllNetworks(mInterfaceName);
    }

    // There might be two possible conditions that there is no
    // valid information to handle this response:
    // 1. A new network request is fired just before getting the response.
    //    As a result, this response is invalid and should be ignored.
    // 2. There is something wrong, and it stops at an abnormal state.
    //    For this case, we should go back DisconnectedState to
    //    recover the state machine.
    // Unfortunatually, we cannot identify the condition without valid information.
    // If condition #1 occurs, and we found that the target SSID is changed,
    // it should transit to L3Connected soon normally, just ignore this message.
    // If condition #2 occurs, clear existing data and notify the client mode
    // via onError callback.
    private boolean isConnectionValid(@Nullable String ssid) {
        if (TextUtils.isEmpty(ssid) || null == mCurrentTofuConfig) {
            handleError(null);
            return false;
        }

        if (!TextUtils.equals(ssid, mCurrentTofuConfig.SSID)) {
            Log.w(TAG, "Target SSID " + mCurrentTofuConfig.SSID
                    + " is different from TOFU returned SSID" + ssid);
            return false;
        }
        return true;
    }

    /** The callbacks object to notify the consumer. */
    public static class InsecureEapNetworkHandlerCallbacks {
        /**
         * When a certificate is accepted, this callback is called.
         *
         * @param ssid SSID of the network.
         */
        public void onAccept(@NonNull String ssid) {}
        /**
         * When a certificate is rejected, this callback is called.
         *
         * @param ssid SSID of the network.
         */
        public void onReject(@NonNull String ssid) {}
        /**
         * When there are no valid data to handle this insecure EAP network,
         * this callback is called.
         *
         * @param ssid SSID of the network, it might be null.
         */
        public void onError(@Nullable String ssid) {}
    }
}
