import Foundation
import Security

public final class KeychainTokenStore: @unchecked Sendable {
    public static let shared = KeychainTokenStore()

    private let service = "com.awaken.sightseeingtravel.auth"
    private let accessTokenKey = "jwt_access_token"
    private let refreshTokenKey = "jwt_refresh_token"
    private let lock = NSLock()

    private init() {}

    public var accessToken: String? {
        get { readString(forKey: accessTokenKey) }
        set {
            if let value = newValue {
                saveString(value, forKey: accessTokenKey)
            } else {
                deleteValue(forKey: accessTokenKey)
            }
        }
    }

    public var refreshToken: String? {
        get { readString(forKey: refreshTokenKey) }
        set {
            if let value = newValue {
                saveString(value, forKey: refreshTokenKey)
            } else {
                deleteValue(forKey: refreshTokenKey)
            }
        }
    }

    public func saveTokens(accessToken: String, refreshToken: String) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
    }

    public func clearTokens() {
        deleteValue(forKey: accessTokenKey)
        deleteValue(forKey: refreshTokenKey)
    }

    private func saveString(_ value: String, forKey key: String) {
        lock.lock()
        defer { lock.unlock() }

        guard let data = value.data(using: .utf8) else { return }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]

        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }

    private func readString(forKey key: String) -> String? {
        lock.lock()
        defer { lock.unlock() }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private func deleteValue(forKey key: String) {
        lock.lock()
        defer { lock.unlock() }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}
