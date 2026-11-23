package com.sgggzg.qmzg.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.sgggzg.qmzg.R;
import com.sgggzg.qmzg.core.AppLog;

/** 主界面：底部导航承载首页 / 设置 / 说明三个页面。 */
public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNavigation = findViewById(R.id.bottom_navigation);

        if (savedInstanceState == null) {
            AppLog.append("应用已启动");
            showFragment(new HomeFragment());
        }

        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_home) {
                showFragment(new HomeFragment());
                return true;
            }
            if (itemId == R.id.navigation_settings) {
                showFragment(new SettingsFragment());
                return true;
            }
            if (itemId == R.id.navigation_about) {
                showFragment(new AboutFragment());
                return true;
            }
            return false;
        });
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
