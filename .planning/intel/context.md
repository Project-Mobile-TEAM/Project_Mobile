# Synthesized Context

> [!NOTE]
> **Historical Snapshot:** File này là snapshot trích xuất ngữ cảnh tại thời điểm Ingest Docs ban đầu (2026-11-10). Nguồn chân lý hiện hành là [.planning/PROJECT.md](../PROJECT.md) và [.planning/ROADMAP.md](../ROADMAP.md).

## Background & Project Goals
- source: docs/README.md
Dự án "UTEChat" là đồ án môn học Lập trình di động Android (Android Mobile Development) xây dựng hệ thống ứng dụng nhắn tin và gọi thoại thời gian thực hoàn chỉnh bao gồm ứng dụng di động Android (client) và hệ thống dịch vụ backend trên đám mây. Dự án đặt mục tiêu đạt điểm tối đa (100 điểm) theo rubric môn học, đồng thời mở rộng nhiều tính năng nâng cao hiện đại.

## Academic & Course Alignment
- source: docs/COURSE_ALIGNMENT.md
Toàn bộ phần mã nguồn Android client được thiết kế bám sát từng bài học (Session 01 đến 07) của giảng viên và đáp ứng đầy đủ yêu cầu chấm điểm trong rubric môn học. Các tính năng nâng cao (WebRTC, ML Kit, AI Bot, TOTP 2FA) đều được tích hợp theo đúng các mẫu kiến trúc kinh điển của môn học (MVVM, LiveData, AppExecutors, Room, Retrofit, WorkManager) mà không làm phá vỡ khuôn mẫu quy định.

## Team Collaboration & Division of Labor
- source: docs/ARCHITECTURE_DECISIONS.md
Nhóm gồm 5 thành viên. Người phụ trách nhóm đảm nhận toàn bộ phần server backend, hạ tầng cloud, CSDL và đóng vai trò giữ hợp đồng chung (API Contract, ERD, WebSocket events), đồng thời phụ trách module Auth phía Android. Bốn thành viên còn lại phụ trách các module Android độc lập (Contacts, Messaging, Media & Call, Settings & Search) dựa trên Fake repository và hợp đồng API trong quá trình song song phát triển.
