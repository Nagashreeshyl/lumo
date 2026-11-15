import SwiftUI
import SwiftyTube
import WebKit

/// Optional Google sign-in. Signed out, Nive works fully as a guest.
/// The password is typed into Google's own page; only the session cookies are kept, in the Keychain.
@Observable
final class Auth {
    private(set) var account: AccountInfo?
    var isSignedIn: Bool { account != nil }

    private let store = SecureStore(service: "com.nagashreeshyl.nive.session")

    /// Restore a saved session at launch.
    func restore() async {
        guard let data = try? store.load(for: "auth"),
              let state = try? JSONDecoder().decode(AuthState.self, from: data) else { return }
        await YT.tube.updateAuth(state)
        account = try? await YT.tube.accountInfo()
    }

    func signIn(cookies: [String: String], sapisid: String) async -> Bool {
        let state = AuthState(cookies: cookies, sapisid: sapisid, visitorData: SessionImporter.extractVisitorData(from: cookies))
        await YT.tube.updateAuth(state)
        guard let info = try? await YT.tube.accountInfo(), !info.name.isEmpty else {
            await YT.tube.updateAuth(.guest)
            return false
        }
        if let data = try? JSONEncoder().encode(state) { try? store.save(data, for: "auth") }
        account = info
        return true
    }

    func signOut() async {
        try? store.delete(for: "auth")
        await YT.tube.updateAuth(.guest)
        account = nil
        let all = WKWebsiteDataStore.allWebsiteDataTypes()
        await WKWebsiteDataStore.default().removeData(ofTypes: all, modifiedSince: .distantPast)
    }
}

/// Google's login page in a web view; harvests the session once the user lands on YouTube Music.
struct LoginView: View {
    @Environment(Auth.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @State private var failed = false

    var body: some View {
        NavigationStack {
            LoginWebView { webView in
                guard let session = await LoginWebviewHarvest.collectSession(from: webView) else { failed = true; return }
                if await auth.signIn(cookies: session.cookies, sapisid: session.sapisid) { dismiss() } else { failed = true }
            }
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle("Sign in")
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            #if os(macOS)
            .frame(minWidth: 520, idealWidth: 560, minHeight: 640, idealHeight: 720)   // a sheet's web view has no size by itself
            #endif
            .alert("Sign-in didn't finish", isPresented: $failed) {
                Button("OK") { dismiss() }
            } message: {
                Text("Please try again.")
            }
        }
    }
}

#if os(iOS)
private typealias WebRepresentable = UIViewRepresentable
#else
private typealias WebRepresentable = NSViewRepresentable
#endif

private struct LoginWebView: WebRepresentable {
    let onLanded: (WKWebView) async -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onLanded: onLanded) }

    private func make(_ coordinator: Coordinator) -> WKWebView {
        let webView = WKWebView()
        webView.customUserAgent = LoginWebviewHarvest.userAgent   // Google needs the desktop flow
        webView.navigationDelegate = coordinator
        webView.load(URLRequest(url: URL(string: "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com")!))
        return webView
    }

    #if os(iOS)
    func makeUIView(context: Context) -> WKWebView { make(context.coordinator) }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
    #else
    func makeNSView(context: Context) -> WKWebView { make(context.coordinator) }
    func updateNSView(_ nsView: WKWebView, context: Context) {}
    #endif

    final class Coordinator: NSObject, WKNavigationDelegate {
        let onLanded: (WKWebView) async -> Void
        private var started = false
        init(onLanded: @escaping (WKWebView) async -> Void) { self.onLanded = onLanded }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard !started, webView.url?.host() == "music.youtube.com" else { return }
            started = true
            Task { await onLanded(webView) }
        }
    }
}
