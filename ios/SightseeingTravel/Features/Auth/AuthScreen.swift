import SwiftUI

public struct AuthScreen: View {
    @EnvironmentObject private var appState: AppState

    @State private var isRegisterMode: Bool = false
    @State private var email: String = ""
    @State private var password: String = ""
    @State private var displayName: String = ""

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    // Header Branding
                    VStack(spacing: 12) {
                        ZStack {
                            Circle()
                                .fill(
                                    LinearGradient(
                                        colors: [.teal, .blue],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 88, height: 88)
                                .shadow(color: .teal.opacity(0.35), radius: 12, y: 6)

                            Image(systemName: "arkit")
                                .font(.system(size: 42, weight: .semibold))
                                .foregroundStyle(.white)
                        }

                        Text("Sightseeing Travel AR")
                            .font(.title.weight(.bold))

                        Text(
                            appState.selectedLanguage == .vi
                                ? "Khám phá văn hóa, lịch sử & ảnh thực tế ảo tăng cường (AR) tại từng điểm đến"
                                : "Explore cultural heritage stories & AR community photo memories on-site"
                        )
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                    }
                    .padding(.top, 20)

                    // Language Switcher
                    Picker("Language", selection: $appState.selectedLanguage) {
                        ForEach(AppLanguage.allCases) { lang in
                            Text(lang.displayName).tag(lang)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 24)

                    // Mode Switcher (Login vs Register)
                    Picker("Auth Mode", selection: $isRegisterMode) {
                        Text(appState.selectedLanguage == .vi ? "Đăng nhập" : "Sign In").tag(false)
                        Text(appState.selectedLanguage == .vi ? "Đăng ký Du khách" : "Tourist Sign Up").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 24)

                    // Credentials Card
                    VStack(spacing: 16) {
                        if isRegisterMode {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(appState.selectedLanguage == .vi ? "Tên hiển thị" : "Display Name")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                TextField(
                                    appState.selectedLanguage == .vi ? "Nguyễn Văn A" : "Alex Traveler",
                                    text: $displayName
                                )
                                .textFieldStyle(.roundedBorder)
                                .textContentType(.name)
                            }
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Email")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            TextField("traveler@example.com", text: $email)
                                .textFieldStyle(.roundedBorder)
                                .keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .textContentType(.emailAddress)
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text(appState.selectedLanguage == .vi ? "Mật khẩu (tối thiểu 8 ký tự)" : "Password (min 8 characters)")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            SecureField("••••••••", text: $password)
                                .textFieldStyle(.roundedBorder)
                                .textContentType(isRegisterMode ? .newPassword : .password)
                        }

                        if let errorMsg = appState.authErrorMessage {
                            HStack(spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundStyle(.red)
                                Text(errorMsg)
                                    .font(.footnote)
                                    .foregroundStyle(.red)
                                Spacer()
                            }
                            .padding(10)
                            .background(Color.red.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        }

                        Button {
                            Task {
                                if isRegisterMode {
                                    await appState.register(
                                        email: email,
                                        password: password,
                                        displayName: displayName,
                                        language: appState.selectedLanguage
                                    )
                                } else {
                                    await appState.login(email: email, password: password)
                                }
                            }
                        } label: {
                            HStack {
                                if appState.isBusy {
                                    ProgressView()
                                        .tint(.white)
                                } else {
                                    Image(systemName: isRegisterMode ? "person.badge.plus" : "arrow.right.circle.fill")
                                    Text(
                                        isRegisterMode
                                            ? (appState.selectedLanguage == .vi ? "Tạo tài khoản Du khách" : "Create Tourist Account")
                                            : (appState.selectedLanguage == .vi ? "Đăng nhập" : "Sign In")
                                    )
                                    .fontWeight(.semibold)
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(isFormValid ? Color.teal : Color.gray.opacity(0.4))
                            .foregroundStyle(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        .disabled(!isFormValid || appState.isBusy)
                    }
                    .padding(20)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .padding(.horizontal, 20)

                    // Quick Role Info Notice
                    VStack(alignment: .leading, spacing: 8) {
                        Label(
                            appState.selectedLanguage == .vi
                                ? "Bắt buộc đăng nhập để truy cập Bản đồ, Câu chuyện văn hóa & AR Camera"
                                : "Authentication is required to access GPS POIs, Cultural Stories & AR Camera",
                            systemImage: "lock.shield.fill"
                        )
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.teal)

                        Text(
                            appState.selectedLanguage == .vi
                                ? "• Tourist: Xem bản đồ, đọc thuyết minh VI/EN, mở AR khi đến gần điểm tham quan & chia sẻ ảnh.\n• Admin: Tạo/chỉnh sửa địa điểm (PendingApproval) & kiểm duyệt ảnh.\n• SuperAdmin: Phê duyệt (Published) hoặc Từ chối địa điểm do Admin tạo."
                                : "• Tourist: Browse GPS map, read VI/EN stories, unlock AR on-site & share photos.\n• Admin: Create/edit POIs (PendingApproval) & moderate community photos.\n• SuperAdmin: Approve (Published) or Reject POIs submitted by Admins."
                        )
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    }
                    .padding(16)
                    .background(Color.teal.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .padding(.horizontal, 20)
                }
                .padding(.bottom, 32)
            }
            .navigationBarHidden(true)
        }
    }

    private var isFormValid: Bool {
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let validAccountId = trimmedEmail.count >= 2
        let validPassword = password.count >= 6
        if isRegisterMode {
            return validAccountId && validPassword && !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return validAccountId && validPassword
    }
}
