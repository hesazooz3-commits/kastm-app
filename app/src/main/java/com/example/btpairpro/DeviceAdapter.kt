package com.example.btpairpro

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(private val listener: OnDeviceClickListener) : RecyclerView.Adapter<DeviceAdapter.VH>() {
    private var devices: List<BluetoothDevice> = emptyList()

    fun submitList(items: List<BluetoothDevice>) {
        devices = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = devices[position]
        holder.name.text = device.name ?: "Unknown"
        holder.address.text = device.address
        holder.itemView.setOnClickListener { listener.onDeviceClick(device) }
    }

    override fun getItemCount(): Int = devices.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_name)
        val address: TextView = view.findViewById(R.id.tv_addr)
    }

    interface OnDeviceClickListener {
        fun onDeviceClick(device: BluetoothDevice)
    }
}
