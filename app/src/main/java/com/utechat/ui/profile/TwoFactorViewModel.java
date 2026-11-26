package com.utechat.ui.profile;

import static androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY;

import android.app.Application;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.ViewModelInitializer;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.AuthResult;
import com.utechat.data.repository.TwoFactorSetup;
import com.utechat.di.Injection;
import java.util.List;

/**
 * Màn bật/tắt 2FA (F-05, F-06). Khoá bí mật và mã dự phòng chỉ nằm trong bộ nhớ của ViewModel (sống qua xoay màn hình,
 * không ghi đĩa, không vào SavedState) vì mã dự phòng chỉ hiện đúng một lần.
 */
public class TwoFactorViewModel extends ViewModel {
    public enum Step { LOADING, ERROR, OFF, SETUP, CODES, ON }

    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(TwoFactorViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new TwoFactorViewModel(Injection.provideAuthRepository(app));
            }));

    private final AuthRepository repository;
    private final MutableLiveData<Step> step = new MutableLiveData<>(Step.LOADING);
    private TwoFactorSetup setup;
    private List<String> backupCodes;

    public TwoFactorViewModel(AuthRepository repository) { this.repository = repository; }

    public LiveData<Step> getStep() { return step; }
    public TwoFactorSetup getSetup() { return setup; }
    public List<String> getBackupCodes() { return backupCodes; }

    /** Hỏi server đang bật hay tắt; Fragment phải observe kết quả để bước được cập nhật. */
    public LiveData<AuthResult<Boolean>> load() {
        step.setValue(Step.LOADING);
        return Transformations.map(repository.getTwoFactorEnabled(), r -> {
            step.setValue(!r.isSuccess() ? Step.ERROR : r.data ? Step.ON : Step.OFF);
            return r;
        });
    }

    public LiveData<AuthResult<TwoFactorSetup>> setup(String password) {
        return Transformations.map(repository.setupTwoFactor(password), r -> {
            if (r.isSuccess()) { setup = r.data; step.setValue(Step.SETUP); }
            return r;
        });
    }

    public LiveData<AuthResult<List<String>>> enable(String code) {
        return Transformations.map(repository.enableTwoFactor(code), r -> {
            if (r.isSuccess()) { backupCodes = r.data; setup = null; step.setValue(Step.CODES); }
            return r;
        });
    }

    public LiveData<AuthResult<Boolean>> disable(String password, String code) {
        return Transformations.map(repository.disableTwoFactor(password, code), r -> {
            if (r.isSuccess()) step.setValue(Step.OFF);
            return r;
        });
    }

    /** Người dùng đã xác nhận lưu mã dự phòng: xoá chúng khỏi bộ nhớ và chuyển sang trạng thái đã bật. */
    public void codesSaved() {
        backupCodes = null;
        step.setValue(Step.ON);
    }
}
