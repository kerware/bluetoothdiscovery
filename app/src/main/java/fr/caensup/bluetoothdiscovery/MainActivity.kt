package fr.caensup.bluetoothdiscovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import fr.caensup.bluetoothdiscovery.ui.theme.BluetoothdiscoveryTheme
import java.util.*

data class FoundDevice(
    val name: String?,
    val address: String,
    val isBonded: Boolean = false,
    val services: List<String> = emptyList(),
    val batteryLevel: String? = null
)

object BluetoothUtils {
    const val BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL_CHAR_UUID = "00002a19-0000-1000-8000-00805f9b34fb"

    private val COMMON_SERVICES = mapOf(
        "00001800-0000-1000-8000-00805f9b34fb" to "Generic Access",
        "00001801-0000-1000-8000-00805f9b34fb" to "Generic Attribute",
        "0000180d-0000-1000-8000-00805f9b34fb" to "Heart Rate",
        BATTERY_SERVICE_UUID to "Battery Service",
        "0000180a-0000-1000-8000-00805f9b34fb" to "Device Information",
        "00001805-0000-1000-8000-00805f9b34fb" to "Current Time Service",
        "00001812-0000-1000-8000-00805f9b34fb" to "Human Interface Device",
        "00001803-0000-1000-8000-00805f9b34fb" to "Link Loss",
        "00001802-0000-1000-8000-00805f9b34fb" to "Immediate Alert",
        "00001804-0000-1000-8000-00805f9b34fb" to "Tx Power"
    )

    fun getServiceName(uuid: String): String? {
        return COMMON_SERVICES[uuid.lowercase(Locale.ROOT)]
    }
}

class MainActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val discoveredDevices = mutableStateListOf<FoundDevice>()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        val foundDevice = FoundDevice(it.name, it.address, it.bondState == BluetoothDevice.BOND_BONDED)
                        if (discoveredDevices.none { d -> d.address == it.address }) {
                            discoveredDevices.add(foundDevice)
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    device?.let { dev ->
                        val index = discoveredDevices.indexOfFirst { it.address == dev.address }
                        if (index != -1) {
                            discoveredDevices[index] = discoveredDevices[index].copy(isBonded = bondState == BluetoothDevice.BOND_BONDED)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(receiver, filter)

        setContent {
            BluetoothdiscoveryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BluetoothScannerScreen(
                        devices = discoveredDevices,
                        onStartScan = { startDiscovery() },
                        onStopScan = { stopDiscovery() },
                        onPairDevice = { address -> pairDevice(address) },
                        onListServices = { address -> discoverServices(address) },
                        onReadBattery = { address -> readBatteryLevel(address) }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!hasPermissions()) {
            Toast.makeText(this, "Permissions manquantes", Toast.LENGTH_SHORT).show()
            return
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth non supporté", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Veuillez activer le Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        discoveredDevices.clear()
        bluetoothAdapter?.startDiscovery()
        Toast.makeText(this, "Recherche lancée", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
        Toast.makeText(this, "Recherche arrêtée", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun pairDevice(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        device?.createBond()
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        device?.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val serviceUuids = gatt.services.map { it.uuid.toString() }
                    val index = discoveredDevices.indexOfFirst { it.address == address }
                    if (index != -1) {
                        discoveredDevices[index] = discoveredDevices[index].copy(services = serviceUuids)
                    }
                }
                gatt.disconnect()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        device?.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val batteryService = gatt.getService(UUID.fromString(BluetoothUtils.BATTERY_SERVICE_UUID))
                    val batteryLevelChar = batteryService?.getCharacteristic(UUID.fromString(BluetoothUtils.BATTERY_LEVEL_CHAR_UUID))
                    if (batteryLevelChar != null) {
                        gatt.readCharacteristic(batteryLevelChar)
                    } else {
                        gatt.disconnect()
                    }
                } else {
                    gatt.disconnect()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                    val index = discoveredDevices.indexOfFirst { it.address == address }
                    if (index != -1) {
                        discoveredDevices[index] = discoveredDevices[index].copy(batteryLevel = "$level%")
                    }
                }
                gatt.disconnect()
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val level = value[0].toInt() and 0xFF
                    val index = discoveredDevices.indexOfFirst { it.address == address }
                    if (index != -1) {
                        discoveredDevices[index] = discoveredDevices[index].copy(batteryLevel = "$level%")
                    }
                }
                gatt.disconnect()
            }
        })
    }

    private fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }
}

@Composable
fun BluetoothScannerScreen(
    devices: List<FoundDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPairDevice: (String) -> Unit,
    onListServices: (String) -> Unit,
    onReadBattery: (String) -> Unit
) {
    val context = LocalContext.current
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            onStartScan()
        } else {
            Toast.makeText(context, "Permissions refusées", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = {
                launcher.launch(permissionsToRequest)
            }) {
                Text("Démarrer la recherche")
            }
            Button(onClick = onStopScan) {
                Text("Stopper la recherche")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Appareils trouvés :",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(devices) { device ->
                DeviceItem(
                    device = device,
                    onPair = { onPairDevice(device.address) },
                    onListServices = { onListServices(device.address) },
                    onReadBattery = { onReadBattery(device.address) }
                )
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: FoundDevice,
    onPair: () -> Unit,
    onListServices: () -> Unit,
    onReadBattery: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = device.name ?: "Nom inconnu",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (device.isBonded) {
                Text(
                    text = "Appairé",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onListServices,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Lister les services disponibles")
                }
                
                if (device.services.isNotEmpty()) {
                    Text(
                        text = "Services :",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    device.services.forEach { serviceUuid ->
                        Column(modifier = Modifier.padding(bottom = 4.dp)) {
                            Text(
                                text = "- $serviceUuid",
                                style = MaterialTheme.typography.bodySmall
                            )
                            BluetoothUtils.getServiceName(serviceUuid)?.let { name ->
                                Text(
                                    text = "  ($name)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                
                                if (serviceUuid.lowercase() == BluetoothUtils.BATTERY_SERVICE_UUID) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Button(
                                            onClick = onReadBattery,
                                            modifier = Modifier.padding(top = 4.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Lire Charge Batterie", style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (device.batteryLevel != null) {
                                            Text(
                                                text = " Niveau : ${device.batteryLevel}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Button(onClick = onPair) {
                    Text("Appairage")
                }
            }
        }
    }
}
