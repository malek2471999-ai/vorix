package com.vorix.scanner

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

data class BTDevice(val name: String, val mac: String, val rssi: Int, val type: String, val device: BluetoothDevice?)

class DeviceAdapter(private val items: MutableList<BTDevice>, private val onClick: (BTDevice)->Unit) : RecyclerView.Adapter<DeviceAdapter.VH>() {
    class VH(v: View): RecyclerView.ViewHolder(v){
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvMac: TextView = v.findViewById(R.id.tvMac)
        val tvRssi: TextView = v.findViewById(R.id.tvRssi)
        val tvType: TextView = v.findViewById(R.id.tvType)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_device, p, false)
        return VH(v)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val d = items[i]
        h.tvName.text = d.name
        h.tvMac.text = d.mac
        h.tvRssi.text = "RSSI: ${d.rssi} dBm ${if(d.rssi>-50) "قريب جدا" else if(d.rssi>-70) "قريب" else "بعيد"}"
        h.tvType.text = d.type
        h.itemView.setOnClickListener { onClick(d) }
    }
    fun update(list: List<BTDevice>){
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {
    private val REQ_CODE = 101
    private lateinit var adapter: DeviceAdapter
    private val devices = mutableListOf<BTDevice>()
    private val map = mutableMapOf<String, BTDevice>()
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var tvStatus: TextView
    private lateinit var filterInput: EditText

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            val name = dev.name ?: result.scanRecord?.deviceName ?: "مجهول"
            val mac = dev.address
            val rssi = result.rssi
            val filter = filterInput.text.toString().trim()
            if (filter.isNotEmpty() && !name.contains(filter, ignoreCase = true) && !mac.contains(filter, ignoreCase = true)) return
            val bt = BTDevice(name, mac, rssi, "BLE", dev)
            map[mac] = bt
            runOnUiThread { refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        filterInput = findViewById(R.id.filterInput)
        val recycler: RecyclerView = findViewById(R.id.recycler)
        val btnScan: Button = findViewById(R.id.btnScan)

        adapter = DeviceAdapter(devices) { showDetails(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val mgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = mgr.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        btnScan.setOnClickListener { toggleScan() }
        checkPerms()
    }

    private fun checkPerms(){
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val need = perms.filter { ContextCompat.checkSelfPermission(this,it)!= PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_CODE)
        else tvStatus.text = "جاهز - اضغط بحث ليبدأ المسح"
    }

    private fun toggleScan(){
        if (scanning) stopScan() else startScan()
    }

    private fun startScan(){
        if (bluetoothAdapter?.isEnabled == false){
            Toast.makeText(this,"شغل البلوتوث أولا",Toast.LENGTH_SHORT).show(); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)!= PackageManager.PERMISSION_GRANTED){
                checkPerms(); return
            }
        }
        map.clear()
        scanning = true
        findViewById<Button>(R.id.btnScan).text = "إيقاف"
        tvStatus.text = "جاري البحث 12 ثانية عن كل الأجهزة..."
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.startScan(null, settings, scanCallback)
        // Classic discovery أيضا
        try { bluetoothAdapter?.startDiscovery() } catch(_:Exception){}
        Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 12000)
    }

    private fun stopScan(){
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch(_:Exception){}
        try { bluetoothAdapter?.cancelDiscovery() } catch(_:Exception){}
        findViewById<Button>(R.id.btnScan).text = "بحث"
        tvStatus.text = "تم العثور على ${map.size} جهاز - اضغط على أي جهاز للتحليل"
        refresh()
    }

    private fun refresh(){
        val sorted = map.values.sortedByDescending { it.rssi }
        adapter.update(sorted)
    }

    private fun showDetails(d: BTDevice){
        // اتصال GATT لقراءة الخدمات
        val details = StringBuilder()
        details.append("الاسم: ${d.name}\n")
        details.append("MAC: ${d.mac}\n")
        details.append("RSSI: ${d.rssi} dBm\n")
        details.append("النوع: ${d.type}\n")
        details.append("Bond State: ${d.device?.bondState}\n")
        details.append("\nجاري قراءة الخدمات...\n")

        val dlg = AlertDialog.Builder(this)
            .setTitle("Vorix - تحليل ${d.name}")
            .setMessage(details.toString())
            .setPositiveButton("اتصال وقراءة الخدمات") { _, _ -> connectGatt(d, details) }
            .setNegativeButton("إغلاق", null)
            .create()
        dlg.show()

        // تحليل أمني سريع بدون اتصال
        val risk = when {
            d.rssi > -50 -> "خطر متوسط - قريب جدا ومكشوف"
            d.name == "مجهول" -> "مخفي - قد يكون جهاز تتبع"
            else -> "منخفض"
        }
        details.append("\n[تقرير Vorix الأمني]\n")
        details.append("مستوى المكاشفة: $risk\n")
        details.append("نصيحة: إذا لا تعرف الجهاز قم بإيقاف البلوتوث فورا\n")
    }

    private fun connectGatt(d: BTDevice, pre: StringBuilder){
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"صلاحية BLUETOOTH_CONNECT مطلوبة", Toast.LENGTH_SHORT).show(); return
        }
        tvStatus.text = "جاري الاتصال بـ ${d.name}..."
        val gatt = d.device?.connectGatt(this, false, object: BluetoothGattCallback(){
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int){
                if (newState == BluetoothProfile.STATE_CONNECTED){
                    runOnUiThread { tvStatus.text = "متصل - جاري اكتشاف الخدمات..." }
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED){
                    runOnUiThread { tvStatus.text = "تم قطع الاتصال" }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int){
                val services = g.services
                val sb = StringBuilder()
                sb.append("=== تقرير Vorix العميق ===\n")
                sb.append("الجهاز: ${d.name} (${d.mac})\n")
                sb.append("عدد الخدمات: ${services.size}\n\n")
                for (s in services){
                    sb.append("Service: ${s.uuid}\n")
                    for (c in s.characteristics){
                        val props = mutableListOf<String>()
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
                        sb.append("  -> Char: ${c.uuid} [${props.joinToString(",")}]\n")
                        if (props.contains("WRITE") && props.contains("READ")){
                            sb.append("     [!] ثغرة محتملة: قراءة+كتابة بدون تشفير\n")
                        }
                    }
                    sb.append("\n")
                }
                sb.append("\n[الخلاصة الأمنية]\n")
                sb.append(if (services.size > 6) "خدمات كثيرة مكشوفة - يحتاج حماية\n" else "مستوى حماية جيد\n")
                sb.append("التوصية: حدث Firmware - غير الاسم الافتراضي - عطل البلوتوث عند عدم الحاجة\n")
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Vorix - الخدمات (${services.size})")
                        .setMessage(sb.toString())
                        .setPositiveButton("حفظ التقرير"){_,_->
                            Toast.makeText(this@MainActivity,"تم نسخ التقرير",Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("إغلاق",null).show()
                    tvStatus.text = "تم التحليل - ${services.size} خدمة مكتشفة"
                }
                g.disconnect()
            }
        })
    }

    override fun onRequestPermissionsResult(c:Int, p:Array<String>, r:IntArray){
        super.onRequestPermissionsResult(c,p,r)
        if (r.all { it==PackageManager.PERMISSION_GRANTED }) tvStatus.text = "تم منح الصلاحيات - اضغط بحث"
        else tvStatus.text = "الصلاحيات مطلوبة للبحث"
    }
}
