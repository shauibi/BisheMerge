// 主界面 Activity，使用 ViewPager2 + TabLayout 实现聊天/模板/知识库/设置四页切换
package com.llmapp.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.llmapp.R
import com.llmapp.databinding.ActivityMainBinding
import com.llmapp.ui.chat.ChatFragment
import com.llmapp.ui.knowledge.KnowledgeFragment
import com.llmapp.ui.settings.SettingsFragment
import com.llmapp.ui.template.PromptTemplateFragment
import com.google.android.material.tabs.TabLayoutMediator

// 应用的入口 Activity，包含四个功能页面的滑动切换
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 切换到聊天标签页
    fun switchToChatTab() {
        binding.viewPager.setCurrentItem(0, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupViewPager()
    }

    // 初始化 ViewPager2 和 TabLayout，配置页面适配器并关联标签
    private fun setupViewPager() {
        val fragments = listOf(
            ChatFragment(),
            PromptTemplateFragment(),
            KnowledgeFragment(),
            SettingsFragment()
        )
        binding.viewPager.adapter = ViewPagerAdapter(this, fragments)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_chat)
                1 -> getString(R.string.tab_templates)
                2 -> getString(R.string.tab_knowledge)
                else -> getString(R.string.tab_settings)
            }
        }.attach()
    }
}

// ViewPager2 页面适配器，管理四个 Fragment 的创建与切换
class ViewPagerAdapter(
    fragmentActivity: AppCompatActivity,
    private val fragments: List<Fragment>
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]
}
