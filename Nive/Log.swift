import os

/// Always-on diagnostics (visible over USB with `idevicesyslog` or Console.app). No personal data is logged.
nonisolated enum Log {
    private static let logger = Logger(subsystem: "com.nagashreeshyl.nive", category: "audio")
    static func info(_ message: String) { logger.notice("[Nive] \(message, privacy: .public)") }
}
