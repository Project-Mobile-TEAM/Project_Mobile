# Hướng dẫn triển khai Coturn (STUN/TURN/TURNS) cho UTEChat trên AWS EC2

Tài liệu này mô tả cách cấu hình máy chủ `coturn` chạy cùng EC2 với Nginx/Spring Boot mà **không xung đột cổng 443**, tuân thủ `ADR-013` và `docs/DEPLOYMENT_SECURITY.md`.

---

## 1. Cấu hình Secondary Elastic IP trên AWS EC2

Để Nginx (HTTPS/WSS) và `coturn` (TURNS TLS 443) cùng dùng cổng 443 trên một instance EC2:

1. Trong AWS Console -> **EC2 -> Network Interfaces**, chọn ENI của instance -> **Actions -> Manage IP addresses -> Assign new IP address** (auto-assign một `<SECONDARY_PRIVATE_IP>`).
2. Tạo một **Elastic IP** mới (`<SECONDARY_PUBLIC_IP>`) và gắn (**Associate**) vào `<SECONDARY_PRIVATE_IP>` vừa tạo.
3. Trỏ bản ghi DNS `A` cho `<turn.utechat.example>` về `<SECONDARY_PUBLIC_IP>` (giữ nguyên `<api.utechat.example>` trỏ về Primary IP của Nginx).
4. Cấu hình Nginx lắng nghe tường minh trên `<PRIMARY_PRIVATE_IP>:443` (không dùng `listen 443` wildcard chung cho mọi IP).

---

## 2. Cấu hình Security Group trên EC2

Mở các cổng Inbound sau cho `<SECONDARY_PRIVATE_IP>` (hoặc toàn Security Group nếu áp dụng chung):

| Giao thức | Cổng | Mục đích |
|---|---|---|
| UDP & TCP | `3478` | STUN / TURN chuẩn |
| TCP & UDP | `443` | TURNS (TLS 1.2/1.3 trên TCP 443 và DTLS 443) vượt tường lửa nghiêm ngặt |
| UDP | `49152-65535` | Dải cổng media relay RTP/DTLS-SRTP |

> Lưu ý: **Tuyệt đối không** mở cổng Spring Boot `8080` ra Internet; cổng `8080` chỉ lắng nghe `127.0.0.1`.

---

## 3. Cài đặt Coturn & Chứng chỉ Let's Encrypt

```bash
sudo apt-get update
sudo apt-get install -y coturn certbot

# Bật dịch vụ coturn
sudo sed -i 's/#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn

# Xin chứng chỉ TLS cho turn.utechat.example (có thể dùng standalone tạm thời hoặc DNS-01)
sudo certbot certonly --standalone -d <turn.utechat.example>
```

### Tự động cấp quyền đọc chứng chỉ và reload khi Certbot gia hạn

Tạo deploy hook tại `/etc/letsencrypt/renewal-hooks/deploy/reload-coturn.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
DOMAIN="<turn.utechat.example>"
TARGET_DIR="/etc/coturn/certs"

mkdir -p "$TARGET_DIR"
cp "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" "${TARGET_DIR}/fullchain.pem"
cp "/etc/letsencrypt/live/${DOMAIN}/privkey.pem" "${TARGET_DIR}/privkey.pem"
chown -R turnserver:turnserver "$TARGET_DIR"
chmod 600 "${TARGET_DIR}/privkey.pem"
chmod 644 "${TARGET_DIR}/fullchain.pem"
systemctl reload coturn || systemctl restart coturn
```

```bash
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-coturn.sh
sudo /etc/letsencrypt/renewal-hooks/deploy/reload-coturn.sh
```

> Khi dùng hook copy chứng chỉ sang `/etc/coturn/certs/`, hãy cập nhật `cert=` và `pkey=` trong `/etc/turnserver.conf` trỏ về `/etc/coturn/certs/fullchain.pem` và `/etc/coturn/certs/privkey.pem`.

---

## 4. Triển khai file cấu hình và khởi chạy

1. Tạo chuỗi bí mật mạnh ngẫu nhiên (chỉ lưu trên server và `.env` của backend):
   ```bash
   openssl rand -hex 32
   ```
2. Sao chép mẫu `backend/deploy/coturn/turnserver.conf` sang `/etc/turnserver.conf`, thay thế toàn bộ placeholder `<SECONDARY_PRIVATE_IP>`, `<SECONDARY_PUBLIC_IP>`, `<turn.utechat.example>`, `<COTURN_SHARED_SECRET>`.
3. Tạo thư mục log và khởi động:
   ```bash
   sudo mkdir -p /var/log/turnserver
   sudo chown turnserver:turnserver /var/log/turnserver
   sudo systemctl enable --now coturn
   sudo systemctl status coturn
   ```

---

## 5. Biến môi trường Backend (`.env`)

Backend sử dụng `TurnCredentialsService` để sinh credential tạm thời HMAC-SHA1 cho mỗi cuộc gọi và **không bao giờ** gửi `COTURN_SHARED_SECRET` về client:

```env
COTURN_URLS=stun:turn.utechat.example:3478,turn:turn.utechat.example:3478?transport=udp,turns:turn.utechat.example:443?transport=tcp
COTURN_SHARED_SECRET=<cùng chuỗi bí mật với static-auth-secret trong turnserver.conf>
COTURN_TTL_SECONDS=3600
COTURN_FALLBACK_STUN_URLS=stun:stun.l.google.com:19302
```

- Khi phát triển cục bộ (local/LAN) mà để trống `COTURN_URLS` hoặc `COTURN_SHARED_SECRET`, backend tự động fallback về `COTURN_FALLBACK_STUN_URLS` (STUN công khai, không kèm username/credential).

---

## 6. Kiểm tra xác thực và SSRF Protection

### Kiểm tra kết nối TURN/TURNS bằng `turnutils_uclient`

```bash
# Tạo username/credential hợp lệ tương tự TurnCredentialsService:
EXPIRY=$(( $(date +%s) + 3600 ))
USERNAME="${EXPIRY}:00000000-0000-0000-0000-000000000001"
CREDENTIAL=$(printf '%s' "$USERNAME" | openssl dgst -sha1 -hmac "<COTURN_SHARED_SECRET>" -binary | base64)

# Kiểm tra TURN UDP 3478
turnutils_uclient -v -u "$USERNAME" -w "$CREDENTIAL" -p 3478 <turn.utechat.example>

# Kiểm tra TURNS TLS TCP 443
turnutils_uclient -v -S -t -u "$USERNAME" -w "$CREDENTIAL" -p 443 <turn.utechat.example>
```

### Kiểm tra bảo mật bắt buộc

- Đảm bảo `turnserver.conf` giữ nguyên toàn bộ các dòng `denied-peer-ip` (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`) để không thể lợi dụng TURN relay truy cập dịch vụ nội bộ hoặc AWS IMDS (`169.254.169.254`).
- Thử thực hiện cuộc gọi thoại thật trên mạng 4G/Wi-Fi trường học (mạng chặn UDP) để xác nhận `turns:<turn.utechat.example>:443?transport=tcp` relay âm thanh thành công.
