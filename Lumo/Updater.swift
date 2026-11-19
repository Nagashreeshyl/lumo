import SwiftUI

/// Checks GitHub for a newer Lumo release and offers it once per launch.
/// iPhone: SideStore installs updates from the Lumo source; Mac: downloads the new DMG.
enum Updater {
    struct Asset: Decodable { let name: String; let browser_download_url: URL }
    struct Release: Decodable, Identifiable {
        let tag_name: String
        let body: String?
        let html_url: URL
        let assets: [Asset]
        var id: String { tag_name }
        var version: String { tag_name.hasPrefix("v") ? String(tag_name.dropFirst()) : tag_name }
    }

    static let latest = URL(string: "https://api.github.com/repos/Nagashreeshyl/lumo/releases/latest")!

    /// "0.10.0" > "0.9.1"; equal or shorter-equal versions are not newer.
    static func isNewer(_ latest: String, than current: String) -> Bool {
        func parts(_ v: String) -> [Int] { v.split(separator: "-")[0].split(separator: ".").map { Int($0) ?? 0 } }
        let a = parts(latest), b = parts(current)
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }

    static var current: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }

    static func check() async -> Release? {
        var request = URLRequest(url: latest, timeoutInterval: 10)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let release = try? JSONDecoder().decode(Release.self, from: data),
              isNewer(release.version, than: current) else { return nil }
        return release
    }
}

private struct UpdatePrompt: ViewModifier {
    @State private var release: Updater.Release?
    @Environment(\.openURL) private var openURL

    func body(content: Content) -> some View {
        content
            .task { release = await Updater.check() }
            .alert("Lumo \(release?.version ?? "") is available", isPresented: Binding(get: { release != nil }, set: { if !$0 { release = nil } }),
                   presenting: release) { r in
                #if os(macOS)
                Button("Download") { openURL(r.assets.first { $0.name.hasSuffix(".dmg") }?.browser_download_url ?? r.html_url) }
                #else
                Button("Open SideStore") { openURL(URL(string: "sidestore://")!) { ok in if !ok { openURL(r.html_url) } } }
                #endif
                Button("Later", role: .cancel) {}
            } message: { r in
                Text(String((r.body ?? "A new version of Lumo is ready.").prefix(400)))
            }
    }
}

extension View {
    func updatePrompt() -> some View { modifier(UpdatePrompt()) }
}
