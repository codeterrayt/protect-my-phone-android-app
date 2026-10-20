import React, { type CSSProperties, type ReactNode } from "react";
import {
  AbsoluteFill,
  Composition,
  Series,
  interpolate,
  spring,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

const C = {
  ink: "#17323b",
  muted: "#60777a",
  paper: "#f3f7f4",
  white: "#ffffff",
  teal: "#248b83",
  mint: "#dff1e9",
  orange: "#f1a268",
  pale: "#eaf0ed",
  line: "#e4ece8",
  dark: "#12272d",
};

const clamp = {
  extrapolateLeft: "clamp" as const,
  extrapolateRight: "clamp" as const,
};

function useReveal(frame: number, delay = 0): CSSProperties {
  const f = frame - delay;
  const { fps } = useVideoConfig();
  const progress = spring({
    frame: f,
    fps,
    config: { damping: 22, stiffness: 90, mass: 0.8 },
  });
  return {
    opacity: interpolate(f, [0, 12], [0, 1], clamp),
    transform: `translateY(${interpolate(progress, [0, 1], [24, 0])}px)`,
  };
}

const Page: React.FC<{ children: ReactNode; dark?: boolean }> = ({
  children,
  dark,
}) => (
  <AbsoluteFill
    style={{
      background: dark
        ? "radial-gradient(ellipse at 78% 22%, #24494c 0%, #17353b 35%, #10272e 76%)"
        : "radial-gradient(ellipse at 84% 16%, #e4f3ec 0%, #f3f7f4 37%, #f5f7f3 100%)",
      color: dark ? C.white : C.ink,
      fontFamily: "Inter, Aptos, Segoe UI, Arial, sans-serif",
      overflow: "hidden",
    }}
  >
    <div
      style={{
        position: "absolute",
        width: 760,
        height: 760,
        right: -300,
        top: 400,
        border: `1px solid ${dark ? "#ffffff12" : "#183c3810"}`,
        borderRadius: "50%",
      }}
    />
    <div
      style={{
        position: "absolute",
        width: 520,
        height: 520,
        right: -180,
        top: 520,
        border: `1px solid ${dark ? "#ffffff10" : "#183c380c"}`,
        borderRadius: "50%",
      }}
    />
    {children}
  </AbsoluteFill>
);

const Brand: React.FC<{ dark?: boolean }> = ({ dark }) => (
  <div style={{ display: "flex", alignItems: "center", gap: 15 }}>
    <div
      style={{
        width: 46,
        height: 46,
        borderRadius: 16,
        display: "grid",
        placeItems: "center",
        background: dark ? "#ffffff18" : C.ink,
      }}
    >
      <svg
        width="25"
        height="27"
        viewBox="0 0 25 27"
        fill="none"
        aria-label="Shield"
      >
        <path
          d="M12.5 2 22 5.4v7.3c0 5.8-3.7 9.6-9.5 12.3C6.7 22.3 3 18.5 3 12.7V5.4L12.5 2Z"
          stroke="#fff"
          strokeWidth="2"
          strokeLinejoin="round"
        />
        <path
          d="m8.5 13.5 2.6 2.6 5.6-5.8"
          stroke="#9ee2bd"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </div>
    <div style={{ fontWeight: 750, fontSize: 25, letterSpacing: -0.8 }}>
      Protect My Phone
    </div>
  </div>
);

const DemoBadge: React.FC<{ dark?: boolean }> = ({ dark }) => (
  <div
    style={{
      border: `1px solid ${dark ? "#ffffff38" : "#d5e3dd"}`,
      color: dark ? "#c6e5d9" : C.muted,
      padding: "10px 15px",
      borderRadius: 999,
      fontSize: 13,
      letterSpacing: 1.25,
      fontWeight: 700,
    }}
  >
    ILLUSTRATIVE DEMO · FICTIONAL DATA
  </div>
);

const Header: React.FC<{ dark?: boolean }> = ({ dark }) => (
  <div
    style={{
      position: "absolute",
      left: 96,
      right: 96,
      top: 58,
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between",
    }}
  >
    <Brand dark={dark} />
    <DemoBadge dark={dark} />
  </div>
);

const Footer: React.FC<{ dark?: boolean; label?: string }> = ({
  dark,
  label = "OWNER-CONTROLLED · DEVICE-TO-DEVICE SMS",
}) => (
  <div
    style={{
      position: "absolute",
      left: 100,
      right: 100,
      bottom: 42,
      display: "flex",
      justifyContent: "space-between",
      alignItems: "center",
      fontSize: 13,
      letterSpacing: 1.1,
      color: dark ? "#bbd2ce" : C.muted,
      fontWeight: 650,
    }}
  >
    <span>{label}</span>
    <span>PROTOTYPE PREVIEW&nbsp;&nbsp; / &nbsp;&nbsp;NO REAL DEVICES</span>
  </div>
);

const Eyebrow: React.FC<{ children: ReactNode; dark?: boolean }> = ({
  children,
  dark,
}) => (
  <div
    style={{
      color: dark ? "#98d5bd" : C.teal,
      textTransform: "uppercase",
      letterSpacing: 2.1,
      fontWeight: 750,
      fontSize: 15,
      marginBottom: 24,
    }}
  >
    {children}
  </div>
);

const Title: React.FC<{
  children: ReactNode;
  dark?: boolean;
  size?: number;
}> = ({ children, dark, size = 76 }) => (
  <div
    style={{
      color: dark ? C.white : C.ink,
      fontSize: size,
      lineHeight: 1.04,
      letterSpacing: -3.2,
      fontWeight: 730,
    }}
  >
    {children}
  </div>
);

const Subcopy: React.FC<{ children: ReactNode; dark?: boolean }> = ({
  children,
  dark,
}) => (
  <div
    style={{
      color: dark ? "#d0dfdc" : C.muted,
      fontSize: 24,
      lineHeight: 1.5,
      maxWidth: 650,
      marginTop: 24,
    }}
  >
    {children}
  </div>
);

const Pill: React.FC<{ children: ReactNode; tint?: string }> = ({
  children,
  tint = C.mint,
}) => (
  <span
    style={{
      display: "inline-flex",
      alignItems: "center",
      padding: "11px 17px",
      borderRadius: 999,
      background: tint,
      color: C.ink,
      fontSize: 15,
      fontWeight: 650,
      marginRight: 10,
      marginTop: 12,
    }}
  >
    {children}
  </span>
);

const Card: React.FC<{ children: ReactNode; style?: CSSProperties }> = ({
  children,
  style,
}) => (
  <div
    style={{
      background: C.white,
      border: `1px solid ${C.line}`,
      boxShadow: "0 24px 80px #183b3210",
      borderRadius: 28,
      ...style,
    }}
  >
    {children}
  </div>
);

const TinyIcon: React.FC<{
  kind: "battery" | "wifi" | "signal" | "pin";
  color?: string;
}> = ({ kind, color = C.teal }) => {
  const paths: Record<string, ReactNode> = {
    battery: (
      <>
        <rect x="3" y="6" width="16" height="12" rx="2" />
        <path d="M21 10v4" />
        <path d="M6.5 9.5h8v5h-8z" fill={color} stroke="none" />
      </>
    ),
    wifi: (
      <>
        <path d="M2.5 8.5a14.4 14.4 0 0 1 19 0" />
        <path d="M6 12a9.3 9.3 0 0 1 12 0" />
        <path d="M9.4 15.5a4.1 4.1 0 0 1 5.2 0" />
        <circle cx="12" cy="19" r="1" fill={color} stroke="none" />
      </>
    ),
    signal: (
      <>
        <path d="M4 20v-4" />
        <path d="M9 20v-8" />
        <path d="M14 20V8" />
        <path d="M19 20V4" />
      </>
    ),
    pin: (
      <>
        <path d="M20 10c0 5-8 12-8 12S4 15 4 10a8 8 0 1 1 16 0Z" />
        <circle cx="12" cy="10" r="2.5" />
      </>
    ),
  };
  return (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke={color}
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {paths[kind]}
    </svg>
  );
};

const PhoneShell: React.FC<{ children: ReactNode; style?: CSSProperties }> = ({
  children,
  style,
}) => (
  <div
    style={{
      width: 410,
      height: 795,
      boxSizing: "border-box",
      borderRadius: 44,
      padding: 13,
      background: "#17272b",
      boxShadow: "0 45px 100px #102a2b2b",
      ...style,
    }}
  >
    <div
      style={{
        height: "100%",
        borderRadius: 33,
        background: "#fbfdfb",
        overflow: "hidden",
        position: "relative",
      }}
    >
      <div
        style={{
          height: 34,
          padding: "10px 23px 0",
          display: "flex",
          justifyContent: "space-between",
          fontSize: 12,
          fontWeight: 700,
          color: C.ink,
        }}
      >
        <span>9:41</span>
        <span>▮▮ &nbsp; ◉ &nbsp; 87%</span>
      </div>
      {children}
      <div
        style={{
          position: "absolute",
          bottom: 10,
          left: "36%",
          width: "28%",
          height: 5,
          borderRadius: 9,
          background: "#182a2c",
        }}
      />
    </div>
  </div>
);

const PhoneDashboard: React.FC<{ frame: number }> = ({ frame }) => {
  const pulse = interpolate(frame % 50, [0, 25, 50], [0.75, 1, 0.75]);
  return (
    <PhoneShell>
      <div style={{ padding: "22px 23px" }}>
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
          }}
        >
          <div style={{ fontSize: 18, fontWeight: 750 }}>Protect My Phone</div>
          <div
            style={{
              width: 34,
              height: 34,
              borderRadius: 12,
              background: C.mint,
              display: "grid",
              placeItems: "center",
              color: C.teal,
              fontWeight: 800,
            }}
          >
            ✓
          </div>
        </div>
        <div style={{ marginTop: 25, fontSize: 13, color: C.muted }}>
          PROTECTED DEVICE
        </div>
        <div style={{ marginTop: 6, fontSize: 25, fontWeight: 740 }}>
          Family phone
        </div>
        <div
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 8,
            marginTop: 12,
            padding: "8px 11px",
            borderRadius: 20,
            background: C.mint,
            color: C.teal,
            fontSize: 12,
            fontWeight: 700,
          }}
        >
          <span
            style={{
              width: 7,
              height: 7,
              borderRadius: "50%",
              background: C.teal,
              opacity: pulse,
            }}
          />{" "}
          PAIRING ACTIVE
        </div>
        <Card
          style={{
            marginTop: 23,
            padding: 17,
            borderRadius: 20,
            background: "#f8fbf9",
            boxShadow: "none",
          }}
        >
          <div style={{ fontSize: 12, color: C.muted, letterSpacing: 0.5 }}>
            LAST REPORTED LOCATION
          </div>
          <div style={{ marginTop: 8, fontSize: 16, fontWeight: 700 }}>
            Sample area · 10:42 AM
          </div>
          <div style={{ marginTop: 5, fontSize: 12, color: C.muted }}>
            Illustrative map location
          </div>
        </Card>
        <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
          <MiniStat icon="battery" label="Battery" value="84%" />
          <MiniStat icon="signal" label="Network" value="LTE" />
        </div>
        <Card
          style={{
            marginTop: 12,
            padding: "15px 16px",
            borderRadius: 18,
            boxShadow: "none",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <TinyIcon kind="wifi" />
            <div>
              <div style={{ fontSize: 11, color: C.muted }}>WI-FI</div>
              <div style={{ fontSize: 14, fontWeight: 700, marginTop: 3 }}>
                Demo network
              </div>
            </div>
            <div
              style={{
                marginLeft: "auto",
                color: C.teal,
                fontSize: 12,
                fontWeight: 700,
              }}
            >
              CONNECTED
            </div>
          </div>
        </Card>
        <div style={{ marginTop: 18, fontSize: 12, color: C.muted }}>
          LAST RESPONSE{" "}
          <strong style={{ color: C.ink, float: "right" }}>Just now</strong>
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          bottom: 0,
          height: 74,
          borderTop: `1px solid ${C.line}`,
          background: "#fff",
          display: "flex",
          justifyContent: "space-around",
          alignItems: "center",
          color: C.teal,
          fontSize: 11,
          fontWeight: 700,
        }}
      >
        <span>
          ⌂<br />
          Home
        </span>
        <span style={{ color: C.muted }}>
          ⌖<br />
          Map
        </span>
        <span style={{ color: C.muted }}>
          ◉<br />
          Devices
        </span>
        <span style={{ color: C.muted }}>
          ⚙<br />
          Setup
        </span>
      </div>
    </PhoneShell>
  );
};

const MiniStat: React.FC<{
  icon: "battery" | "signal";
  label: string;
  value: string;
}> = ({ icon, label, value }) => (
  <Card style={{ flex: 1, padding: 15, borderRadius: 18, boxShadow: "none" }}>
    <TinyIcon kind={icon} />
    <div style={{ fontSize: 11, color: C.muted, marginTop: 10 }}>{label}</div>
    <div style={{ fontSize: 17, fontWeight: 750, marginTop: 2 }}>{value}</div>
  </Card>
);

const IntroScene: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <Page>
      <Header />
      <div style={{ position: "absolute", left: 110, top: 250, width: 880 }}>
        <div style={useReveal(frame)}>
          <Eyebrow>Private by design · built for Android</Eyebrow>
        </div>
        <div style={useReveal(frame, 5)}>
          <Title>
            Your devices.
            <br />
            Ready when it matters.
          </Title>
        </div>
        <div style={useReveal(frame, 11)}>
          <Subcopy>
            An owner-controlled safety prototype that sends commands and status
            directly between paired phones over SMS.
          </Subcopy>
        </div>
        <div style={{ ...useReveal(frame, 18), marginTop: 23 }}>
          <Pill>NO APP SERVER</Pill>
          <Pill>ENCRYPTED COMMANDS</Pill>
          <Pill>ANDROID</Pill>
        </div>
        <div
          style={{
            ...useReveal(frame, 23),
            marginTop: 40,
            display: "flex",
            alignItems: "center",
            gap: 13,
            color: C.muted,
            fontSize: 16,
          }}
        >
          <span style={{ width: 34, height: 2, background: C.orange }} />
          Your phone stays in your hands. Your data stays on your devices.
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          right: 240,
          top: 170,
          transform: `translateY(${interpolate(frame, [0, 120], [0, -18])}px)`,
        }}
      >
        <PhoneDashboard frame={frame} />
      </div>
      <div
        style={{
          position: "absolute",
          right: 120,
          top: 410,
          width: 190,
          height: 190,
          borderRadius: "50%",
          background: "#f1a2681d",
        }}
      />
      <Footer />
    </Page>
  );
};

const DataRow: React.FC<{
  icon: "battery" | "signal" | "wifi" | "pin";
  label: string;
  value: string;
  detail: string;
  frame: number;
  delay: number;
}> = ({ icon, label, value, detail, frame, delay }) => (
  <div
    style={{
      ...useReveal(frame, delay),
      display: "flex",
      alignItems: "center",
      padding: "22px 26px",
      borderBottom: `1px solid ${C.line}`,
    }}
  >
    <div
      style={{
        width: 52,
        height: 52,
        borderRadius: 17,
        background: C.mint,
        display: "grid",
        placeItems: "center",
      }}
    >
      <TinyIcon kind={icon} />
    </div>
    <div style={{ marginLeft: 18, flex: 1 }}>
      <div style={{ color: C.muted, fontSize: 14 }}>{label}</div>
      <div style={{ fontWeight: 720, fontSize: 22, marginTop: 5 }}>{value}</div>
    </div>
    <div style={{ color: C.muted, fontSize: 14 }}>{detail}</div>
  </div>
);

const StatusScene: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <Page>
      <Header />
      <div style={{ position: "absolute", left: 110, top: 270, width: 650 }}>
        <div style={useReveal(frame)}>
          <Eyebrow>One SMS · one useful reply</Eyebrow>
        </div>
        <div style={useReveal(frame, 4)}>
          <Title size={70}>
            A clear picture,
            <br />
            all at once.
          </Title>
        </div>
        <div style={useReveal(frame, 10)}>
          <Subcopy>
            Ask a paired device for a compact status report. Battery, network,
            Wi-Fi, last location and the response time arrive together.
          </Subcopy>
        </div>
        <Card
          style={{
            ...useReveal(frame, 18),
            marginTop: 38,
            padding: "20px 24px",
            borderRadius: 22,
            width: 515,
          }}
        >
          <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
            <div
              style={{
                width: 38,
                height: 38,
                borderRadius: 13,
                display: "grid",
                placeItems: "center",
                background: C.ink,
                color: "white",
                fontWeight: 800,
              }}
            >
              ↗
            </div>
            <div>
              <div style={{ color: C.muted, fontSize: 12, letterSpacing: 1 }}>
                COMMAND SENT
              </div>
              <div style={{ fontSize: 17, fontWeight: 750, marginTop: 4 }}>
                STATUS
              </div>
            </div>
            <div
              style={{
                marginLeft: "auto",
                color: C.teal,
                fontSize: 13,
                fontWeight: 700,
              }}
            >
              ENCRYPTED SMS
            </div>
          </div>
        </Card>
      </div>
      <div style={{ position: "absolute", top: 205, right: 155, width: 815 }}>
        <Card
          style={{
            ...useReveal(frame, 8),
            padding: "0 16px",
            overflow: "hidden",
            borderRadius: 30,
          }}
        >
          <div
            style={{
              padding: "27px 28px",
              display: "flex",
              alignItems: "center",
              borderBottom: `1px solid ${C.line}`,
            }}
          >
            <div>
              <div style={{ fontSize: 13, color: C.muted, letterSpacing: 1 }}>
                DEVICE STATUS
              </div>
              <div style={{ fontSize: 27, fontWeight: 750, marginTop: 4 }}>
                Family phone
              </div>
            </div>
            <div
              style={{
                marginLeft: "auto",
                padding: "10px 14px",
                borderRadius: 999,
                background: C.mint,
                color: C.teal,
                fontSize: 12,
                fontWeight: 750,
              }}
            >
              REPLIED · JUST NOW
            </div>
          </div>
          <DataRow
            icon="battery"
            label="Battery"
            value="84% · charging"
            detail="Healthy"
            frame={frame}
            delay={12}
          />
          <DataRow
            icon="signal"
            label="Mobile network"
            value="LTE · available"
            detail="Signal reported"
            frame={frame}
            delay={17}
          />
          <DataRow
            icon="wifi"
            label="Wi-Fi"
            value="Demo network"
            detail="Connected"
            frame={frame}
            delay={22}
          />
          <DataRow
            icon="pin"
            label="Last location"
            value="Sample area"
            detail="10:42 AM"
            frame={frame}
            delay={27}
          />
          <div
            style={{
              padding: "18px 27px",
              display: "flex",
              gap: 10,
              color: C.muted,
              fontSize: 13,
            }}
          >
            <span style={{ color: C.teal }}>●</span> Values shown are fictional
            examples, not a live phone report.
          </div>
        </Card>
      </div>
      <Footer />
    </Page>
  );
};

const MapIllustration: React.FC<{ frame: number }> = ({ frame }) => {
  const dash = interpolate(frame % 90, [0, 90], [0, 1]);
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        overflow: "hidden",
        borderRadius: 26,
        background: "#e8f0e9",
      }}
    >
      <svg
        viewBox="0 0 900 600"
        width="100%"
        height="100%"
        preserveAspectRatio="xMidYMid slice"
        style={{ position: "absolute", inset: 0 }}
      >
        <path
          d="M-40 110 180 170 360 90 560 160 930 30M-10 300 180 250 360 325 585 270 950 370M-30 500 160 420 370 480 560 410 940 530M130 -40 205 180 160 350 270 650M370 -40 330 180 420 330 365 640M620 -30 540 140 650 350 580 630M820 -40 740 200 815 370 760 640"
          fill="none"
          stroke="#fff"
          strokeWidth="20"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M-20 200 170 230 370 145 550 210 930 115M10 390 190 330 390 400 590 340 935 445"
          fill="none"
          stroke="#d4e1d8"
          strokeWidth="4"
          strokeLinecap="round"
        />
        <path
          d="M190 250c65 20 120 30 185 75s95 50 175 16"
          fill="none"
          stroke={C.teal}
          strokeWidth="7"
          strokeDasharray="16 12"
          strokeDashoffset={dash * 28}
          strokeLinecap="round"
        />
        <g opacity="0.6" fill="#b4d9c1">
          <rect x="50" y="25" width="170" height="58" rx="25" />
          <rect x="420" y="60" width="120" height="48" rx="20" />
          <rect x="700" y="215" width="150" height="55" rx="23" />
          <rect x="285" y="470" width="140" height="65" rx="25" />
        </g>
      </svg>
      <MapMarker x="26%" y="43%" label="A" delay={0} frame={frame} />
      <MapMarker x="61%" y="60%" label="B" delay={8} frame={frame} />
      <div
        style={{
          position: "absolute",
          right: 21,
          top: 20,
          background: "#ffffffed",
          borderRadius: 15,
          padding: "10px 14px",
          fontSize: 12,
          fontWeight: 700,
          color: C.muted,
        }}
      >
        ILLUSTRATIVE MAP · NOT LIVE
      </div>
    </div>
  );
};

const MapMarker: React.FC<{
  x: string;
  y: string;
  label: string;
  delay: number;
  frame: number;
}> = ({ x, y, label, delay, frame }) => {
  const { fps } = useVideoConfig();
  const scale = spring({
    frame: frame - delay,
    fps,
    config: { damping: 12, stiffness: 130 },
  });
  return (
    <div
      style={{
        position: "absolute",
        left: x,
        top: y,
        transform: `translate(-50%,-50%) scale(${scale})`,
        width: 58,
        height: 58,
        borderRadius: "50% 50% 50% 10%",
        rotate: "-45deg",
        background: C.teal,
        display: "grid",
        placeItems: "center",
        boxShadow: "0 10px 28px #176c6044",
      }}
    >
      <span
        style={{
          transform: "rotate(45deg)",
          fontSize: 20,
          color: "white",
          fontWeight: 800,
        }}
      >
        {label}
      </span>
    </div>
  );
};

const LocationScene: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <Page>
      <Header />
      <div style={{ position: "absolute", left: 110, top: 280, width: 570 }}>
        <div style={useReveal(frame)}>
          <Eyebrow>Location that respects the device</Eyebrow>
        </div>
        <div style={useReveal(frame, 5)}>
          <Title size={70}>
            Get the latest
            <br />
            when it moves.
          </Title>
        </div>
        <div style={useReveal(frame, 11)}>
          <Subcopy>
            Request a one-time location, or let an opted-in phone send an update
            after it moves. Turn sharing off whenever you choose.
          </Subcopy>
        </div>
        <Card
          style={{
            ...useReveal(frame, 19),
            marginTop: 31,
            width: 490,
            padding: 22,
            display: "flex",
            alignItems: "center",
            gap: 16,
          }}
        >
          <div
            style={{
              width: 50,
              height: 50,
              background: C.mint,
              borderRadius: 17,
              display: "grid",
              placeItems: "center",
            }}
          >
            <TinyIcon kind="pin" />
          </div>
          <div>
            <div style={{ fontSize: 12, color: C.muted }}>
              LAST UPDATE · SAMPLE DATA
            </div>
            <div style={{ fontSize: 18, fontWeight: 730, marginTop: 5 }}>
              Family phone · 10:42 AM
            </div>
          </div>
        </Card>
        <div
          style={{
            ...useReveal(frame, 24),
            marginTop: 20,
            fontSize: 15,
            lineHeight: 1.5,
            color: C.muted,
            maxWidth: 505,
          }}
        >
          Background location access is optional and is required for movement
          updates. Android and device battery policies can delay them.
        </div>
      </div>
      <Card
        style={{
          ...useReveal(frame, 7),
          position: "absolute",
          top: 203,
          right: 125,
          width: 900,
          height: 630,
          overflow: "hidden",
          padding: 12,
          borderRadius: 34,
        }}
      >
        <MapIllustration frame={frame} />
        <div
          style={{
            position: "absolute",
            left: 28,
            bottom: 28,
            padding: "15px 18px",
            borderRadius: 18,
            background: "#fff",
            boxShadow: "0 9px 25px #183c3215",
            display: "flex",
            alignItems: "center",
            gap: 12,
          }}
        >
          <div style={{ display: "flex" }}>
            <span
              style={{
                width: 30,
                height: 30,
                borderRadius: "50%",
                background: C.teal,
                border: "2px solid white",
                display: "grid",
                placeItems: "center",
                color: "white",
                fontSize: 11,
                fontWeight: 800,
              }}
            >
              A
            </span>
            <span
              style={{
                width: 30,
                height: 30,
                borderRadius: "50%",
                background: C.orange,
                border: "2px solid white",
                display: "grid",
                placeItems: "center",
                color: "white",
                fontSize: 11,
                fontWeight: 800,
                marginLeft: -7,
              }}
            >
              B
            </span>
          </div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 750 }}>
              Two paired devices
            </div>
            <div style={{ fontSize: 11, color: C.muted, marginTop: 3 }}>
              Fictional map preview
            </div>
          </div>
        </div>
      </Card>
      <Footer label="LOCATION SHARED OVER SMS · MAP ARTWORK IS SCHEMATIC" />
    </Page>
  );
};

const ActionScene: React.FC = () => {
  const frame = useCurrentFrame();
  const sound = interpolate(frame % 45, [0, 22, 45], [0.2, 1, 0.2]);
  return (
    <Page dark>
      <Header dark />
      <div style={{ position: "absolute", left: 110, top: 280, width: 670 }}>
        <div style={useReveal(frame)}>
          <Eyebrow dark>Useful actions, with clear limits</Eyebrow>
        </div>
        <div style={useReveal(frame, 5)}>
          <Title dark size={70}>
            Find it. Lock it.
            <br />
            Show a message.
          </Title>
        </div>
        <div style={useReveal(frame, 12)}>
          <Subcopy dark>
            Send an owner-authorized command to start an audible alert or
            request a lock. Screen owner text needs Android managed-device
            enrollment.
          </Subcopy>
        </div>
        <div
          style={{
            ...useReveal(frame, 20),
            marginTop: 36,
            display: "flex",
            gap: 14,
          }}
        >
          <div
            style={{
              border: "1px solid #ffffff27",
              borderRadius: 18,
              padding: "17px 20px",
              background: "#ffffff0c",
              width: 205,
            }}
          >
            <div style={{ color: "#abd9c5", fontSize: 12, letterSpacing: 1 }}>
              REMOTE ACTION
            </div>
            <div style={{ fontSize: 19, fontWeight: 740, marginTop: 8 }}>
              Play sound
            </div>
            <div
              style={{
                marginTop: 14,
                height: 24,
                display: "flex",
                alignItems: "center",
                gap: 4,
              }}
            >
              {[8, 16, 23, 11, 19, 26, 13, 8, 20, 15, 25, 10, 18, 8].map(
                (h, i) => (
                  <div
                    key={i}
                    style={{
                      width: 5,
                      height: h * sound,
                      borderRadius: 3,
                      background: "#9be0bc",
                    }}
                  />
                ),
              )}
            </div>
          </div>
          <div
            style={{
              border: "1px solid #ffffff27",
              borderRadius: 18,
              padding: "17px 20px",
              background: "#ffffff0c",
              width: 205,
            }}
          >
            <div style={{ color: "#abd9c5", fontSize: 12, letterSpacing: 1 }}>
              DEVICE CONTROL
            </div>
            <div style={{ fontSize: 19, fontWeight: 740, marginTop: 8 }}>
              Lock request
            </div>
            <div style={{ marginTop: 12, fontSize: 13, color: "#d0dfdc" }}>
              Requires owner setup
            </div>
          </div>
        </div>
        <div
          style={{
            ...useReveal(frame, 25),
            marginTop: 25,
            fontSize: 15,
            color: "#b4ceca",
          }}
        >
          SMS carrier delivery · Android may restrict background actions · no
          anti-theft guarantee
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          right: 180,
          top: 215,
          width: 650,
          height: 630,
          borderRadius: 36,
          border: "1px solid #ffffff24",
          background: "#ffffff0b",
          padding: 30,
          boxSizing: "border-box",
          ...useReveal(frame, 12),
        }}
      >
        <div style={{ color: "#badbd2", fontSize: 13, letterSpacing: 1.4 }}>
          OWNER MESSAGE PREVIEW · DEMO ONLY
        </div>
        <div
          style={{
            marginTop: 26,
            height: 500,
            borderRadius: 28,
            background: "linear-gradient(145deg,#f7fbf8,#dfeee6)",
            display: "grid",
            placeItems: "center",
            position: "relative",
            overflow: "hidden",
          }}
        >
          <div
            style={{
              position: "absolute",
              inset: 0,
              background:
                "radial-gradient(circle at 50% 40%,#ffffffb8,transparent 54%)",
            }}
          />
          <div
            style={{
              zIndex: 1,
              textAlign: "center",
              padding: 50,
              color: C.ink,
            }}
          >
            <div
              style={{
                margin: "0 auto 20px",
                width: 74,
                height: 74,
                borderRadius: 25,
                background: C.ink,
                display: "grid",
                placeItems: "center",
              }}
            >
              <svg width="36" height="36" viewBox="0 0 25 27" fill="none">
                <path
                  d="M12.5 2 22 5.4v7.3c0 5.8-3.7 9.6-9.5 12.3C6.7 22.3 3 18.5 3 12.7V5.4L12.5 2Z"
                  stroke="white"
                  strokeWidth="2"
                />
                <path
                  d="m8.5 13.5 2.6 2.6 5.6-5.8"
                  stroke="#9ee2bd"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
            </div>
            <div
              style={{
                fontSize: 16,
                letterSpacing: 1,
                color: C.teal,
                fontWeight: 750,
              }}
            >
              PLEASE RETURN THIS PHONE
            </div>
            <div
              style={{
                fontSize: 27,
                lineHeight: 1.25,
                letterSpacing: -0.5,
                fontWeight: 740,
                marginTop: 13,
              }}
            >
              Contact the owner
              <br />
              using the details on file.
            </div>
            <div style={{ marginTop: 22, fontSize: 14, color: C.muted }}>
              This is a fictional preview.
            </div>
          </div>
        </div>
      </div>
      <Footer
        dark
        label="CONSENT FIRST · DEVICE ADMIN / DEVICE OWNER REQUIREMENTS APPLY"
      />
    </Page>
  );
};

const ClosingScene: React.FC = () => {
  const frame = useCurrentFrame();
  const glow = interpolate(frame % 90, [0, 45, 90], [0.85, 1, 0.85]);
  return (
    <Page dark>
      <Header dark />
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: 255,
          textAlign: "center",
        }}
      >
        <div
          style={{
            ...useReveal(frame),
            display: "flex",
            justifyContent: "center",
          }}
        >
          <Eyebrow dark>Protect My Phone · open-source prototype</Eyebrow>
        </div>
        <div
          style={{
            ...useReveal(frame, 6),
            transform: `scale(${interpolate(spring({ frame: frame - 6, fps: 30, config: { damping: 18 } }), [0, 1], [0.94, 1])})`,
          }}
        >
          <Title dark size={88}>
            Safety starts with consent.
          </Title>
        </div>
        <div
          style={{
            ...useReveal(frame, 12),
            display: "flex",
            justifyContent: "center",
          }}
        >
          <Subcopy dark>
            Offline SMS pairing. Transparent permissions. Device-side control.
          </Subcopy>
        </div>
        <div
          style={{
            ...useReveal(frame, 22),
            margin: "48px auto 0",
            display: "flex",
            justifyContent: "center",
            gap: 16,
          }}
        >
          {[
            "No account or app server",
            "Encrypted SMS commands",
            "Owner-controlled sharing",
          ].map((text, i) => (
            <div
              key={text}
              style={{
                padding: "17px 22px",
                border: "1px solid #ffffff27",
                background: "#ffffff0b",
                borderRadius: 18,
                color: "#e4f0ec",
                fontSize: 16,
                boxShadow: i === 1 ? `0 0 ${22 * glow}px #78c9a51e` : undefined,
              }}
            >
              ✓&nbsp;&nbsp;{text}
            </div>
          ))}
        </div>
        <div
          style={{
            ...useReveal(frame, 29),
            margin: "43px auto 0",
            color: "#b2c8c4",
            fontSize: 15,
          }}
        >
          Use only with explicit consent. Android, carrier and OEM restrictions
          apply.
        </div>
      </div>
      <Footer
        dark
        label="PROTECT MY PHONE · ANDROID · APACHE-2.0 SOURCE LICENSE"
      />
    </Page>
  );
};

const DemoVideo: React.FC = () => (
  <AbsoluteFill>
    <Series>
      <Series.Sequence durationInFrames={120}>
        <IntroScene />
      </Series.Sequence>
      <Series.Sequence durationInFrames={135}>
        <StatusScene />
      </Series.Sequence>
      <Series.Sequence durationInFrames={135}>
        <LocationScene />
      </Series.Sequence>
      <Series.Sequence durationInFrames={135}>
        <ActionScene />
      </Series.Sequence>
      <Series.Sequence durationInFrames={90}>
        <ClosingScene />
      </Series.Sequence>
    </Series>
  </AbsoluteFill>
);

export const MyComposition = () => (
  <Composition
    id="ProtectMyPhoneDemo"
    component={DemoVideo}
    durationInFrames={615}
    fps={30}
    width={1920}
    height={1080}
  />
);
