package com.example.wifiscanner.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.wifiscanner.ui.ScanFragment
import com.example.wifiscanner.ui.ViewFragment

class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ViewFragment()
            1 -> ScanFragment()
            else -> throw IllegalArgumentException("Invalid tab position")
        }
    }
}
