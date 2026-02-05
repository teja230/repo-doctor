const badgeStyle = {
  padding: "10px 16px",
  borderRadius: 999,
  border: "1px solid rgba(45, 212, 191, 0.5)",
  backgroundColor: "rgba(15, 118, 110, 0.2)",
  color: "#99f6e4",
  fontSize: 20,
  fontWeight: 600,
  letterSpacing: "0.4px",
};

export function OgImage() {
  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        display: "flex",
        flexDirection: "column",
        justifyContent: "space-between",
        padding: "64px",
        backgroundColor: "#0b1220",
        backgroundImage:
          "radial-gradient(circle at 20% 20%, rgba(16, 185, 129, 0.35) 0%, rgba(16, 185, 129, 0) 45%)," +
          "radial-gradient(circle at 80% 0%, rgba(14, 165, 233, 0.35) 0%, rgba(14, 165, 233, 0) 45%)," +
          "linear-gradient(135deg, #0b1220 0%, #0f172a 60%, #064e3b 100%)",
        color: "#e2e8f0",
        fontFamily: "ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: "16px" }}>
        <div
          style={{
            width: 56,
            height: 56,
            borderRadius: 14,
            border: "1px solid rgba(16, 185, 129, 0.5)",
            backgroundColor: "rgba(16, 185, 129, 0.18)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: 28,
          }}
        >
          🩺
        </div>
        <div
          style={{
            fontSize: 28,
            fontWeight: 700,
            letterSpacing: "0.6px",
            color: "#d1fae5",
          }}
        >
          RepoDoctor
        </div>
      </div>

      <div style={{ maxWidth: 880, display: "flex", flexDirection: "column", gap: "18px" }}>
        <div style={{ fontSize: 64, fontWeight: 800, lineHeight: 1.1, color: "#f8fafc" }}>
          Autonomous build-fixing agent
        </div>
        <div style={{ fontSize: 28, lineHeight: 1.4, color: "#cbd5f5" }}>
          Diagnose build failures, generate patches with deep reasoning, and verify fixes in a sandboxed loop.
        </div>
        <div style={{ fontSize: 22, color: "#a7f3d0", fontWeight: 600 }}>
          Powered by Gemini 3
        </div>
      </div>

      <div style={{ display: "flex", gap: "12px" }}>
        <div style={badgeStyle}>Diagnose</div>
        <div style={badgeStyle}>Patch</div>
        <div style={badgeStyle}>Run</div>
        <div style={badgeStyle}>Verify</div>
      </div>
    </div>
  );
}
