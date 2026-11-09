# DẤU VIỄN DU (v1.1) — Cultural Discovery, GPS Map, AR Camera & Community Photo Platform

**Dấu Viễn Du (phiên bản v1.1)** là nền tảng du lịch văn hóa đa ngôn ngữ (`vi` / `en`) dành cho du khách quốc tế và nội địa, kết hợp định vị bản đồ GPS thời gian thực, công nghệ Thực tế tăng cường (AR Camera) hiển thị câu chuyện văn hóa và những bức ảnh đẹp nhất của cộng đồng ngay tại điểm đến, cùng quy trình quản trị và phê duyệt 3 cấp (`Tourist`, `Admin`, `SuperAdmin`).

---

## 1. Kiến trúc & Công nghệ (Tech Stack)

| Thành phần | Công nghệ sử dụng | Mô tả kiến trúc |
| :--- | :--- | :--- |
| **Backend API** | **ASP.NET Core 8.0 Web API** | Kiến trúc **Modular Monolith + Clean Architecture + API-First** (`/api/v1`), JWT Authentication tự chủ + Refresh Token Rotation, RBAC 3 cấp (`Tourist`, `Admin`, `SuperAdmin`). |
| **Database** | **PostgreSQL 16** | Phân tách 4 Schemas độc lập theo Module: `identity`, `attractions`, `ar`, `media`. |
| **Object Storage** | **Contabo Object Storage (S3-Compatible)** | Tích hợp qua `AWSSDK.S3` (`ForcePathStyle = true`), lưu ảnh bìa địa điểm và ảnh cộng đồng dưới prefix `dauviendu_cloud/` trong bucket đã cấu hình. |
| **iOS Native** | **SwiftUI (iOS 17+)** | **Apple MapKit** (`Map`, `UserAnnotation`, `Annotation`) + **ARKit / SceneKit (`ARSCNView`)** hiển thị thẻ văn hóa AR & Top 10 khung ảnh cộng đồng trong không gian 3D. |
| **Android Native** | **Kotlin + Jetpack Compose (Material 3)** | **MapLibre Native SDK** với **OpenFreeMap Bright** (`https://tiles.openfreemap.org/styles/bright`) & **OpenStreetMap** + **CameraX & ARCore/Sensor 3D Projection**. |

---

## 2. Cấu trúc Thư mục Dự án

```text
DauVienDuApp/
├── backend/
│   ├── SightseeingTravel.sln
│   ├── Dockerfile
│   ├── src/
│   │   ├── SightseeingTravel.SharedKernel/         # Base Entities, Result<T>, GeoCalculator (Haversine), Contracts
│   │   ├── SightseeingTravel.Api/                  # Host, JWT/RBAC Policies, Contabo S3 Service, Migrations & Seeder
│   │   └── Modules/
│   │       ├── Identity/                           # Module 1: Auth, JWT, RefreshToken, 3 Roles (Tourist/Admin/SuperAdmin)
│   │       ├── Attractions/                        # Module 2: POI/Pin, Stories (vi/en), SuperAdmin Approval Workflow
│   │       ├── ArExperience/                       # Module 3: AR Info Cards, Geofence Verification, AR Scene Context
│   │       └── CommunityMedia/                     # Module 4: Photo Upload (Contabo S3), Like, Report & Moderation
│   └── tests/
│       ├── SightseeingTravel.UnitTests/            # Unit Tests cho Domain, State Machine, Haversine, Image Validator
│       └── SightseeingTravel.IntegrationTests/     # Integration Tests kiểm thử luồng liên module E2E
├── ios/
│   ├── Package.swift
│   └── SightseeingTravel/                          # SwiftUI + Apple MapKit + ARKit Native App
├── android/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── app/                                        # Kotlin + Jetpack Compose + MapLibre/OpenFreeMap/OSM + AR Camera
├── deploy/
│   └── nginx/nginx.conf                            # Mẫu reverse proxy thay thế khi không dùng FlashPanel
├── docker-compose.yml                              # FlashPanel PostgreSQL 18 + ASP.NET Core API; FlashPanel quản lý HTTPS/reverse proxy
└── .env.example                                    # Cấu hình mẫu cho VPS Contabo & Contabo Object Storage
```

---

## 3. Hướng dẫn triển khai trên VPS Contabo qua FlashPanel

1. Sao chép file cấu hình môi trường:
   ```bash
   cp .env.example .env
   ```
2. Cấu hình `.env` cho database/user PostgreSQL `awkdvd` đã tạo trong FlashPanel, gồm mật khẩu database, `JWT_SIGNING_KEY`, email SuperAdmin khởi tạo và mật khẩu bootstrap. Tạo `JWT_SIGNING_KEY` ngẫu nhiên tối thiểu 32 byte, ví dụ bằng `openssl rand -hex 32`. Điền thông tin **Contabo Object Storage** (`CONTABO_S3_SERVICE_URL`, `CONTABO_S3_BUCKET_NAME`, `CONTABO_S3_PUBLIC_BASE_URL`, `CONTABO_S3_KEY_PREFIX`, `CONTABO_S3_ACCESS_KEY`, `CONTABO_S3_SECRET_KEY`) nếu bật upload ảnh. Mặc định `CONTABO_S3_KEY_PREFIX=dauviendu_cloud`; mọi ảnh được upload qua app sẽ nằm dưới prefix này trong bucket.
   Bucket không cần bật Public Access: API upload object không gắn ACL công khai và trả URL GET ký số có hiệu lực 15 phút. URL ký số là bearer link trong thời gian này; chỉ người có URL mới đọc được object.
3. Khởi chạy bằng Docker Compose:
   ```bash
   docker compose up -d --build
   ```
4. Compose dùng PostgreSQL FlashPanel hiện có trên loopback. API container dùng host networking và chỉ lắng nghe `127.0.0.1:8080`; cấu hình site `dauviendu.awaken.asia` reverse proxy tới `http://127.0.0.1:8080` và giữ TLS đang cài.
5. Khi database chưa có SuperAdmin, API tạo một tài khoản từ `BOOTSTRAP_ADMIN_EMAIL` và `BOOTSTRAP_ADMIN_PASSWORD`. Sau khi đăng nhập quản trị lần đầu, xóa `BOOTSTRAP_ADMIN_PASSWORD` khỏi cấu hình môi trường của VPS.
6. Truy cập OpenAPI / Swagger tại `https://dauviendu.awaken.asia/swagger`.

Production không tự tạo tài khoản mẫu hoặc POI demo. Hãy tạo POI thực tế từ trang quản trị sau khi khởi tạo SuperAdmin.

---

## 4. Chạy Kiểm thử Tự động (Automated Tests)

```bash
cd backend
dotnet test SightseeingTravel.sln --configuration Release
```
