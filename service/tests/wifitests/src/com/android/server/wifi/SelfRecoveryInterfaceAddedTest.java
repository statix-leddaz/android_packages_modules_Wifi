/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;

import androidx.test.filters.SmallTest;

import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * Unit tests for {@link SelfRecovery}, with the
 * {@link R.bool#config_wifiInterfaceAddedSelfRecoveryEnabled} flag turned on.
 */
@SmallTest
public class SelfRecoveryInterfaceAddedTest extends WifiBaseTest {
    SelfRecovery mSelfRecovery;
    MockResources mResources;
    @Mock Context mContext;
    @Mock ActiveModeWarden mActiveModeWarden;
    @Mock Clock mClock;
    @Mock WifiNative mWifiNative;
    final ArgumentCaptor<HalDeviceManager.SubsystemRestartListener> mRestartListenerCaptor =
            ArgumentCaptor.forClass(HalDeviceManager.SubsystemRestartListener.class);

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mResources = new MockResources();
        mResources.setBoolean(R.bool.config_wifiInterfaceAddedSelfRecoveryEnabled, true);
        when(mContext.getResources()).thenReturn(mResources);
        mSelfRecovery = new SelfRecovery(mContext, mActiveModeWarden, mClock, mWifiNative);
        verify(mWifiNative).registerSubsystemRestartListener(mRestartListenerCaptor.capture());
        doAnswer((invocation) -> {
            mRestartListenerCaptor.getValue().onSubsystemRestart();
            return true;
        }).when(mWifiNative).startSubsystemRestart();
    }

    /**
     * Verifies that when the self recovery on interface added flag is enabled, a STA interface down
     * event will not disable wifi.
     */
    @Test
    public void testStaIfaceDownDoesNotDisableWifi() {
        mSelfRecovery.trigger(SelfRecovery.REASON_STA_IFACE_DOWN);
        verify(mActiveModeWarden, never()).recoveryDisableWifi();
    }

    @Test
    public void testStaIfaceAddedTriggersSelfRecovery() {
        mSelfRecovery.trigger(SelfRecovery.REASON_IFACE_ADDED);
        verify(mWifiNative).startSubsystemRestart();
    }
}
