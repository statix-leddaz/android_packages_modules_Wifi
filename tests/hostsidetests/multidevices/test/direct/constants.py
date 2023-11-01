#  Copyright (C) 2023 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

# Lint as: python3

import dataclasses
import enum

WIFI_DIRECT_SNIPPET_PACKAGE_NAME = 'com.google.snippet.wifi.direct'

EXTRA_WIFI_P2P_GROUP = 'p2pGroupInfo'
EXTRA_WIFI_STATE = 'wifi_p2p_state'

WIFI_P2P_CONNECTED = 'CONNECTED'
WIFI_P2P_CONNECTION_CHANGED_ACTION = (
    'android.net.wifi.p2p.CONNECTION_STATE_CHANGE'
)
WIFI_P2P_DISCOVERY_CHANGED_ACTION = (
    'android.net.wifi.p2p.DISCOVERY_STATE_CHANGE'
)
WIFI_P2P_PEERS_CHANGED_ACTION = 'android.net.wifi.p2p.PEERS_CHANGED'
WIFI_P2P_STATE_CHANGED_ACTION = 'android.net.wifi.p2p.STATE_CHANGED'
WIFI_P2P_THIS_DEVICE_CHANGED_ACTION = (
    'android.net.wifi.p2p.THIS_DEVICE_CHANGED'
)


@enum.unique
class ActionListenerOnFailure(enum.IntEnum):
  """Indicates the failure reason of the initiation of the action.

  https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.ActionListener#onFailure(int)
  """

  ERROR = 0
  P2P_UNSUPPORTED = 1
  BUSY = 2


@enum.unique
class ExtraWifiState(enum.IntEnum):
  """Indicates whether Wi-Fi p2p is enabled or disabled.

  https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager#EXTRA_WIFI_STATE
  """

  WIFI_P2P_STATE_UNKNOWN = 0
  WIFI_P2P_STATE_DISABLED = 1
  WIFI_P2P_STATE_ENABLED = 2


@dataclasses.dataclass(frozen=True)
class WifiP2pConfig:
  """Represents a Wi-Fi P2p configuration for setting up a connection.

  https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig
  """

  persistent_mode: bool | None = None
  device_address: str | None = None
  group_client_ip_provisioning_mode: int | None = None
  group_operating_band: int | None = None
  group_operating_frequency: int | None = None
  network_name: str | None = None
  passphrase: str | None = None

  def to_dict(self) -> dict[str, bool | int | str | None]:
    """Converts this WifiP2pConfig to a dictionary."""
    return {k: v for k, v in self.__dict__.items() if v is not None}

