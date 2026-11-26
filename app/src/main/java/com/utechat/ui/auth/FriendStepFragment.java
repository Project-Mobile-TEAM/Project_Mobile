package com.utechat.ui.auth;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.format.DateUtils;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.data.repository.FriendChallenge;
import com.utechat.databinding.FragmentFriendStepBinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bước nhận diện bạn bè (F-04): chọn đúng 3 bạn trong 15 người; sai thì có bộ mới, sai 3 lần bị khoá 15 phút. */
public class FriendStepFragment extends Fragment {
    private FragmentFriendStepBinding binding;
    private AuthViewModel viewModel;
    private final Set<String> selected = new LinkedHashSet<>();
    private CountDownTimer timer;
    private Adapter adapter;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentFriendStepBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = AuthViewModel.of(this);
        binding.toolbar.setNavigationOnClickListener(v -> backToLogin());
        adapter = new Adapter();
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);
        binding.btnConfirm.setOnClickListener(v -> submit());
        FriendChallenge ch = viewModel.getFriendChallenge();
        if (ch == null) { backToLogin(); return; } // Tiến trình bị thu hồi: token đã mất, đăng nhập lại.
        render(ch);
    }

    private void render(FriendChallenge ch) {
        selected.clear();
        adapter.set(ch.candidates);
        updateConfirm();
        if (ch.lastAttemptWrong) {
            binding.tvStatus.setText(getString(R.string.friend_step_wrong, ch.attemptsLeft));
            binding.tvStatus.setVisibility(View.VISIBLE);
        } else {
            binding.tvStatus.setVisibility(View.GONE);
        }
        if (timer != null) timer.cancel();
        long remaining = Math.max(0, ch.expiresAtMillis - System.currentTimeMillis());
        timer = new CountDownTimer(remaining, 1000) {
            @Override public void onTick(long left) {
                binding.tvTimer.setText(getString(R.string.friend_step_expires, DateUtils.formatElapsedTime((left + 999) / 1000)));
            }
            @Override public void onFinish() { refresh(); }
        }.start();
    }

    private void updateConfirm() { binding.btnConfirm.setEnabled(selected.size() == FriendChallenge.PICK); }

    private void submit() {
        binding.btnConfirm.setEnabled(false);
        viewModel.submitFriendChoice(new ArrayList<>(selected)).observe(getViewLifecycleOwner(), result -> {
            if (result.isSuccess()) {
                Navigation.findNavController(binding.getRoot()).navigate(R.id.action_friend_to_main);
            } else if ("FRIEND_WRONG".equals(result.errorCode)) {
                render(viewModel.getFriendChallenge());
            } else if ("FRIEND_CHALLENGE_EXPIRED".equals(result.errorCode)) {
                refresh();
            } else {
                // LOCKED (sai 3 lần), OTP_EXPIRED (token tạm hết hạn) hoặc lỗi mạng: về màn đăng nhập, nói rõ lý do.
                Snackbar.make(requireActivity().findViewById(android.R.id.content),
                        "OTP_EXPIRED".equals(result.errorCode) ? R.string.auth_error_otp_expired : AuthErrors.message(result.errorCode),
                        Snackbar.LENGTH_LONG).show();
                if ("NETWORK".equals(result.errorCode)) updateConfirm(); else backToLogin();
            }
        });
    }

    /** Bộ hết hạn: xin bộ mới, không tính lần thử. */
    private void refresh() {
        viewModel.refreshFriendChallenge().observe(getViewLifecycleOwner(), result -> {
            if (result.isSuccess()) render(result.data);
            else backToLogin();
        });
    }

    private void backToLogin() {
        Navigation.findNavController(binding.getRoot()).popBackStack(R.id.loginFragment, false);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (timer != null) timer.cancel();
        binding = null;
    }

    /** Danh sách 15 người với ô chọn; không cho chọn quá 3 người. */
    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private List<FriendChallenge.Candidate> items = new ArrayList<>();

        void set(List<FriendChallenge.Candidate> list) {
            items = list;
            notifyDataSetChanged(); // shortcut: bộ chỉ 15 phần tử và thay nguyên bộ, không cần DiffUtil.
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder((MaterialCheckBox) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_friend_candidate, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            FriendChallenge.Candidate c = items.get(position);
            h.box.setOnCheckedChangeListener(null);
            h.box.setText(c.displayName);
            h.box.setChecked(selected.contains(c.id));
            h.box.setOnCheckedChangeListener((b, checked) -> {
                if (checked && selected.size() >= FriendChallenge.PICK) { b.setChecked(false); return; }
                if (checked) selected.add(c.id); else selected.remove(c.id);
                updateConfirm();
            });
        }

        @Override public int getItemCount() { return items.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final MaterialCheckBox box;
            Holder(MaterialCheckBox box) { super(box); this.box = box; }
        }
    }
}
