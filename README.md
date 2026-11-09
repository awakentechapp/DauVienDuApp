# SIGHTSEEING TRAVEL — Cultural Discovery, GPS Map, AR Camera & Community Photo Platform

**SIGHTSEEING TRAVEL** là nền tảng du lịch văn hóa đa ngôn ngữ (`vi` / `en`) dành cho du khách quốc tế và nội địa, kết hợp định vị bản đồ GPS thời gian thực, công nghệ Thực tế tăng cường (AR Camera) hiển thị câu chuyện văn hóa và những bức ảnh đẹp nhất của cộng đồng ngay tại điểm đến, cùng quy trình quản trị và phê duyệt 3 cấp (`Tourist`, `Admin`, `SuperAdmin`).

---

## 1. Kiến trúc & Công nghệ (Tech Stack)

| Thành phần | Công nghệ sử dụng | Mô tả kiến trúc |
| :--- | :--- | :--- |
| **Backend API** | **ASP.NET Core 8.0 Web API** | Kiến trúc **Modular Monolith + Clean Architecture + API-First** (`/api/v1`), JWT Authentication tự chủ + Refresh Token Rotation, RBAC 3 cấp (`Tourist`, `Admin`, `SuperAdmin`). |
| **Database** | **PostgreSQL 16** | Phân tách 4 Schemas độc lập theo Module: `identity`, `attractions`, `ar`, `media`. |
| **Object Storage** | **Contabo Object Storage (S3-Compatible)** | Tích hợp qua `AWSSDK.S3` (`ForcePathStyle = true`), lưu trữ ảnh bìa địa điểm và ảnh cộng đồng do du khách chụp tại điểm đến. |
| **iOS Native** | **SwiftUI (iOS 17+)** | **Apple MapKit** (`Map`, `UserAnnotation`, `Annotation`) + **ARKit / SceneKit (`ARSCNView`)** hiển thị thẻ văn hóa AR & Top 10 khung ảnh cộng đồng trong không gian 3D. |
| **Android Native** | **Kotlin + Jetpack Compose (Material 3)** | **MapLibre Native SDK** với **OpenFreeMap Bright** (`https://tiles.openfreemap.org/styles/bright`) & **OpenStreetMap** + **CameraX & ARCore/Sensor 3D Projection**. |

---

## 2. Cấu trúc Thư mục Dự án

```text
SIGHTSEEING TRAVEL/
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
│   └── nginx/nginx.conf                            # Reverse Proxy cấu hình cho VPS Contabo
├── docker-compose.yml                              # Khởi chạy trọn bộ PostgreSQL 16 + ASP.NET Core API + Nginx
└── .env.example                                    # Cấu hình mẫu cho VPS Contabo & Contabo Object Storage
```

---

## 3. Hướng dẫn Triển khai trên VPS Contabo & Contabo Object Storage

1. Sao chép file cấu hình môi trường:
   ```bash
   cp .env.example .env
   ```
2. Cập nhật thông tin **Contabo Object Storage** (`CONTABO_S3_SERVICE_URL`, `CONTABO_S3_BUCKET_NAME`, `CONTABO_S3_ACCESS_KEY`, `CONTABO_S3_SECRET_KEY`) và mật khẩu PostgreSQL trong `.env`.
3. Khởi chạy bằng Docker Compose:
   ```bash
   docker compose up -d --build
   ```
4. Truy cập tài liệu OpenAPI / Swagger UI tại: `http://<vps-ip>/swagger`
5. Tài khoản mặc định được khởi tạo tự động (Data Seeder):
   - **SuperAdmin:** `superadmin@sightseeing.vn` / `SuperAdmin@123`
   - **Admin:** `admin@sightseeing.vn` / `Admin@12345`
   - **Tourist:** `tourist@sightseeing.vn` / `Tourist@123`

---

## 4. Chạy Kiểm thử Tự động (Automated Tests)

```bash
cd backend
dotnet test SightseeingTravel.sln --configuration Release
```
