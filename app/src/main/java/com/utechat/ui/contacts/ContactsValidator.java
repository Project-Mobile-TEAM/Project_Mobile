package com.utechat.ui.contacts;

/** Chuẩn hóa số dùng khi tìm người; backend vẫn phải kiểm tra lại dữ liệu nhận được. */
public final class ContactsValidator {
    private ContactsValidator() { }

    /**
     * Chấp nhận số Việt Nam 10 chữ số bắt đầu bằng 0 hoặc số quốc tế có dấu +.
     * Bỏ dấu cách, ngoặc và gạch nối để hỗ trợ số được dán vào ô nhập.
     * Trả null khi sai định dạng; không xóa chữ để biến dữ liệu sai thành một số khác.
     * Chỉ kiểm tra cấu trúc E.164, không xác nhận thuê bao có tồn tại hay không.
     */
    public static String normalize(String raw) {
        if (raw == null || !raw.matches("[+0-9 ()-]+")) return null;

        String value = raw.replaceAll("[ ()-]", "");
        // Số trong nước bỏ số 0 đầu và thêm mã quốc gia Việt Nam.
        if (value.matches("0[1-9][0-9]{8}")) value = "+84" + value.substring(1);

        // Hợp đồng của project yêu cầu 8 đến 15 chữ số, mã quốc gia không bắt đầu bằng 0.
        return value.matches("\\+[1-9][0-9]{7,14}") ? value : null;
    }
}
