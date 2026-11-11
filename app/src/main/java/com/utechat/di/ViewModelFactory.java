package com.utechat.di;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.utechat.data.repository.HealthRepository;
import com.utechat.ui.splash.HealthViewModel;

/** Constructor injection cho ViewModel có repository; không giữ Activity trong ViewModel. */
public class ViewModelFactory implements ViewModelProvider.Factory {
    private final HealthRepository health;
    public ViewModelFactory(HealthRepository health) { this.health = health; }

    @NonNull @Override @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass == HealthViewModel.class) return (T) new HealthViewModel(health);
        throw new IllegalArgumentException("ViewModel chưa đăng ký: " + modelClass.getName());
    }
}
