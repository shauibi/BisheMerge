package com.llmapp.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.llmapp.databinding.ItemModelSettingBinding

// 模型列表 RecyclerView 适配器（用于旧版 XML 布局，当前已迁移至 Compose）
class ModelListAdapter(
    private val onSelectModel: (Pair<String, String>) -> Unit
) : ListAdapter<Pair<String, String>, ModelListAdapter.ViewHolder>(DiffCallback()) {

    private var selectedModelPath: String? = null

    // 更新当前选中模型，刷新选中态
    fun setSelectedModel(modelPath: String?) {
        selectedModelPath = modelPath
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModelSettingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (name, path) = getItem(position)
        holder.bind(name, path, path == selectedModelPath)
    }

    inner class ViewHolder(private val binding: ItemModelSettingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(name: String, path: String, isSelected: Boolean) {
            binding.tvModelName.text = name
            binding.rbSelected.isChecked = isSelected
            binding.root.setOnClickListener {
                onSelectModel(name to path)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Pair<String, String>>() {
        override fun areItemsTheSame(old: Pair<String, String>, new: Pair<String, String>): Boolean {
            return old.second == new.second
        }
        override fun areContentsTheSame(old: Pair<String, String>, new: Pair<String, String>): Boolean {
            return old == new
        }
    }
}
