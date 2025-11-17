package com.yyj.aiapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.yyj.aiapp.databinding.ActivityMainBinding
import com.yyj.aiapp.ui.config.ConfigFragment
import com.yyj.aiapp.ui.home.HomeFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String = TAG_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchFragment(HomeFragment(), TAG_HOME)
            binding.bottomNav.selectedItemId = R.id.menu_home
        } else {
            currentTag = savedInstanceState.getString(STATE_TAG, TAG_HOME)
            binding.bottomNav.selectedItemId =
                if (currentTag == TAG_HOME) R.id.menu_home else R.id.menu_config
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> {
                    if (currentTag != TAG_HOME) {
                        switchFragment(HomeFragment(), TAG_HOME)
                    }
                    true
                }
                R.id.menu_config -> {
                    if (currentTag != TAG_CONFIG) {
                        switchFragment(ConfigFragment(), TAG_CONFIG)
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAG, currentTag)
    }

    private fun switchFragment(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        val fragmentToShow = existing ?: fragment
        supportFragmentManager.commit {
            replace(R.id.nav_host_container, fragmentToShow, tag)
        }
        currentTag = tag
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_CONFIG = "config"
        private const val STATE_TAG = "state:current_tag"
    }
}
