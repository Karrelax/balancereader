package com.bodyscalereader.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bodyscalereader.app.databinding.ItemMeasurementBinding
import com.bodyscalereader.app.database.Measurement
import java.text.SimpleDateFormat
import java.util.*

class MeasurementAdapter(
    private val onItemClick: (Measurement) -> Unit
) : ListAdapter<Measurement, MeasurementAdapter.ViewHolder>(DiffCallback()) {
    
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMeasurementBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding, onItemClick)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), dateFormat)
    }
    
    class ViewHolder(
        private val binding: ItemMeasurementBinding,
        private val onItemClick: (Measurement) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(measurement: Measurement, dateFormat: SimpleDateFormat) {
            binding.tvDate.text = dateFormat.format(measurement.timestamp)
            binding.tvWeight.text = String.format("%.2f kg", measurement.weight)
            binding.tvBMI.text = String.format("BMI: %.1f", measurement.bmi)
            binding.tvFat.text = String.format("Grasa: %.1f%%", measurement.bodyFat)
            
            itemView.setOnClickListener { onItemClick(measurement) }
        }
    }
    
    class DiffCallback : DiffUtil.ItemCallback<Measurement>() {
        override fun areItemsTheSame(oldItem: Measurement, newItem: Measurement): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Measurement, newItem: Measurement): Boolean {
            return oldItem.timestamp == newItem.timestamp &&
                    oldItem.weight == newItem.weight
        }
    }
}