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

data class FoundDevice(
    val name: String?,
    val address: String,
    val isBonded: Boolean = false,
    val services: List<String> = emptyList()
)

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
                        onListServices = { address -> discoverServices(address) }
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
    onListServices: (String) -> Unit
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
                    onListServices = { onListServices(device.address) }
                )
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: FoundDevice,
    onPair: () -> Unit,
    onListServices: () -> Unit
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
                        Text(
                            text = "- $serviceUuid",
                            style = MaterialTheme.typography.bodySmall
                        )
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
