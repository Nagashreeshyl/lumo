import AVFoundation
import AVKit
import SwiftUI
import WebKit
#if os(iOS)
import UIKit
typealias PlatformImage = UIImage
#else
import AppKit
typealias PlatformImage = NSImage
#endif

/// Small iPhone/Mac differences in one place, so shared code stays readable.
enum Platform {
    /// iOS needs an active playback audio session; macOS has none.
    static func activateAudio() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
    }

    static func configureAudio() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        #endif
    }
}

extension Color {
    static var appBackground: Color {
        #if os(iOS)
        Color(.systemBackground)
        #else
        Color(nsColor: .windowBackgroundColor)
        #endif
    }
}

extension ToolbarItemPlacement {
    static var leadingBar: ToolbarItemPlacement {
        #if os(iOS)
        .topBarLeading
        #else
        .navigation
        #endif
    }
    static var trailingBar: ToolbarItemPlacement {
        #if os(iOS)
        .topBarTrailing
        #else
        .primaryAction
        #endif
    }
}

extension View {
    /// Small title on iPhone; Mac windows have no large titles anyway.
    func inlineTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    /// Search field that's always visible (iPhone: under the title; Mac: in the toolbar).
    func alwaysVisibleSearch(text: Binding<String>, prompt: String) -> some View {
        #if os(iOS)
        searchable(text: text, placement: .navigationBarDrawer(displayMode: .always), prompt: prompt)
        #else
        searchable(text: text, placement: .toolbar, prompt: prompt)
        #endif
    }
}

/// System AirPlay / output picker.
struct AirPlayButton: View {
    var body: some View { RoutePicker() }

    #if os(iOS)
    private struct RoutePicker: UIViewRepresentable {
        func makeUIView(context: Context) -> AVRoutePickerView {
            let v = AVRoutePickerView()
            v.tintColor = .white
            v.activeTintColor = .white
            return v
        }
        func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
    }
    #else
    private struct RoutePicker: NSViewRepresentable {
        func makeNSView(context: Context) -> AVRoutePickerView {
            let v = AVRoutePickerView()
            v.isRoutePickerButtonBordered = false
            return v
        }
        func updateNSView(_ nsView: AVRoutePickerView, context: Context) {}
    }
    #endif
}

/// A WKWebView in SwiftUI on both platforms.
struct WebView: View {
    let make: () -> WKWebView
    var body: some View { Rep(make: make) }

    #if os(iOS)
    private struct Rep: UIViewRepresentable {
        let make: () -> WKWebView
        func makeUIView(context: Context) -> WKWebView { make() }
        func updateUIView(_ uiView: WKWebView, context: Context) {}
    }
    #else
    private struct Rep: NSViewRepresentable {
        let make: () -> WKWebView
        func makeNSView(context: Context) -> WKWebView { make() }
        func updateNSView(_ nsView: WKWebView, context: Context) {}
    }
    #endif
}
