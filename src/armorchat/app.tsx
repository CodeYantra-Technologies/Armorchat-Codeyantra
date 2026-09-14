import { useEffect, useState, type ReactNode } from "react";
import {
  ChevronLeft,
  ChevronRight,
  MessageSquare,
  Settings,
  Users,
} from "lucide-react";

type Screen =
  | "splash"
  | "welcome"
  | "create"
  | "restore"
  | "home"
  | "about"
  | "support";

type Tab = "chats" | "contacts" | "settings";
type ThemeMode = "system" | "dark" | "light";

type Identity = {
  id: string;
  publicLabel: string;
  createdAt: number;
};

const IDENTITY_KEY = "armorchat.identity";
const THEME_KEY = "armorchat.theme";

function readIdentity(): Identity | null {
  try {
    const raw = localStorage.getItem(IDENTITY_KEY);
    return raw ? (JSON.parse(raw) as Identity) : null;
  } catch {
    return null;
  }
}

function readTheme(): ThemeMode {
  try {
    const raw = localStorage.getItem(THEME_KEY);
    if (raw === "dark" || raw === "light" || raw === "system") return raw;
  } catch {
    /* ignore */
  }
  return "system";
}

function resolvedTheme(mode: ThemeMode): "dark" | "light" {
  if (mode === "dark") return "dark";
  if (mode === "light") return "light";
  if (typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: light)").matches) {
    return "light";
  }
  return "dark";
}

export function ArmorChatApp() {
  const [screen, setScreen] = useState<Screen>("splash");
  const [tab, setTab] = useState<Tab>("chats");
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [themeMode, setThemeMode] = useState<ThemeMode>("dark");
  const [theme, setTheme] = useState<"dark" | "light">("dark");
  const [restoreValue, setRestoreValue] = useState("");
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    const storedIdentity = readIdentity();
    const storedTheme = readTheme();
    setIdentity(storedIdentity);
    setThemeMode(storedTheme);
    setTheme(resolvedTheme(storedTheme));
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    const timer = window.setTimeout(() => {
      setScreen(readIdentity() ? "home" : "welcome");
    }, 1400);
    return () => window.clearTimeout(timer);
  }, [hydrated]);

  function persistTheme(mode: ThemeMode) {
    setThemeMode(mode);
    setTheme(resolvedTheme(mode));
    localStorage.setItem(THEME_KEY, mode);
  }

  function persistIdentity(next: Identity) {
    setIdentity(next);
    localStorage.setItem(IDENTITY_KEY, JSON.stringify(next));
  }

  function createIdentity(prefix: string) {
    const id = crypto.randomUUID();
    persistIdentity({
      id,
      publicLabel: `${prefix}-${id.slice(0, 8)}`,
      createdAt: Date.now(),
    });
    setScreen("home");
    setTab("chats");
  }

  function restoreIdentity() {
    if (!restoreValue.trim()) {
      setRestoreError("Recovery material is empty.");
      return;
    }
    setRestoreError(null);
    createIdentity("restored");
  }

  return (
    <div className="ac-root min-h-dvh bg-studio text-fg" data-theme={theme}>
      <div className="mx-auto flex min-h-dvh max-w-6xl flex-col items-center justify-center gap-6 px-4 py-6 md:flex-row md:items-center md:gap-16 md:px-10">
        <aside className="hidden max-w-sm md:block">
          <p className="font-display text-xs font-semibold tracking-[0.22em] text-muted uppercase">
            CodeYantra Technologies
          </p>
          <h1 className="font-display mt-3 text-4xl font-semibold tracking-tight text-fg">
            ArmorChat
          </h1>
          <p className="mt-4 text-[15px] leading-relaxed text-muted">
            Private conversations. Your identity. Your control.
          </p>
          <p className="mt-6 text-sm text-subtle">
            Native Android foundation. This is an interactive preview of the
            Phase 0 application shell.
          </p>
          <p className="mt-8 text-xs tracking-wide text-subtle">Secured by Veltrix</p>
        </aside>

        <div className="w-full max-w-[390px] md:w-[390px] md:shrink-0">
          <div className="md:rounded-phone md:bg-[#0a0c12] md:p-[10px] md:shadow-phone">
            <div className="relative flex min-h-[100dvh] flex-col overflow-hidden bg-bg text-fg md:min-h-[760px] md:rounded-[26px]">
              <StatusBar />
              <div className="relative flex min-h-0 flex-1 flex-col">
                {screen === "splash" && <SplashScreen />}
                {screen === "welcome" && (
                  <WelcomeScreen
                    onCreate={() => setScreen("create")}
                    onRestore={() => {
                      setRestoreError(null);
                      setScreen("restore");
                    }}
                  />
                )}
                {screen === "create" && (
                  <CreateScreen
                    onBack={() => setScreen("welcome")}
                    onContinue={() => createIdentity("dev")}
                  />
                )}
                {screen === "restore" && (
                  <RestoreScreen
                    value={restoreValue}
                    error={restoreError}
                    onChange={setRestoreValue}
                    onBack={() => setScreen("welcome")}
                    onContinue={restoreIdentity}
                  />
                )}
                {screen === "home" && (
                  <HomeScreen
                    tab={tab}
                    onTab={setTab}
                    themeMode={themeMode}
                    onTheme={persistTheme}
                    onAbout={() => setScreen("about")}
                    onSupport={() => setScreen("support")}
                    identity={identity}
                  />
                )}
                {screen === "about" && (
                  <AboutScreen onBack={() => setScreen("home")} />
                )}
                {screen === "support" && (
                  <SupportScreen onBack={() => setScreen("home")} />
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function StatusBar() {
  return (
    <div className="flex items-center justify-between px-6 pt-3 pb-1 text-[11px] font-medium text-fg">
      <span className="tabular-nums">23:05</span>
      <span className="flex items-center gap-1.5 text-fg">
        <span className="inline-block h-2 w-3.5 rounded-[1px] border border-current opacity-80" />
        <span className="inline-block h-2.5 w-1.5 rounded-[1px] bg-current" />
      </span>
    </div>
  );
}

function SplashScreen() {
  return (
    <div className="relative flex flex-1 flex-col items-center justify-center px-8 pb-10">
      <Mark size={148} />
      <h1 className="font-display mt-5 text-[28px] font-semibold tracking-[0.08em] text-fg">
        ArmorChat
      </h1>
      <p className="mt-2 text-sm text-muted">by CodeYantra Technologies</p>
      <p className="absolute bottom-8 text-xs tracking-wide text-subtle">
        Secured by Veltrix
      </p>
    </div>
  );
}

function WelcomeScreen({
  onCreate,
  onRestore,
}: {
  onCreate: () => void;
  onRestore: () => void;
}) {
  return (
    <div className="flex flex-1 flex-col px-6 pb-8 pt-8">
      <Mark size={88} />
      <h2 className="font-display mt-8 text-[30px] leading-[1.15] font-semibold tracking-tight text-fg">
        Private conversations.
        <br />
        Your identity.
        <br />
        Your control.
      </h2>
      <div className="mt-auto flex flex-col gap-3">
        <button
          type="button"
          onClick={onCreate}
          className="h-14 rounded-lg bg-fg px-4 text-[15px] font-medium text-bg transition-transform duration-150 active:scale-[0.98]"
        >
          Create ArmorChat Identity
        </button>
        <button
          type="button"
          onClick={onRestore}
          className="h-14 rounded-lg border border-line bg-transparent px-4 text-[15px] font-medium text-fg transition-transform duration-150 active:scale-[0.98]"
        >
          Restore Existing Identity
        </button>
        <p className="mt-3 text-center text-xs tracking-wide text-subtle">
          Secured by Veltrix
        </p>
      </div>
    </div>
  );
}

function CreateScreen({
  onBack,
  onContinue,
}: {
  onBack: () => void;
  onContinue: () => void;
}) {
  return (
    <div className="flex flex-1 flex-col px-6 pb-8">
      <BackBar title="Create identity" onBack={onBack} />
      <p className="mt-2 text-[15px] leading-relaxed text-muted">
        This step uses a non-production local identifier so the application
        shell can be completed. A production cryptographic identity protocol
        will be selected later.
      </p>
      <button
        type="button"
        onClick={onContinue}
        className="mt-auto h-14 rounded-lg bg-fg text-[15px] font-medium text-bg transition-transform duration-150 active:scale-[0.98]"
      >
        Create ArmorChat Identity
      </button>
    </div>
  );
}

function RestoreScreen({
  value,
  error,
  onChange,
  onBack,
  onContinue,
}: {
  value: string;
  error: string | null;
  onChange: (value: string) => void;
  onBack: () => void;
  onContinue: () => void;
}) {
  return (
    <div className="flex flex-1 flex-col px-6 pb-8">
      <BackBar title="Restore identity" onBack={onBack} />
      <p className="mt-2 text-[15px] leading-relaxed text-muted">
        Paste recovery material from a future ArmorChat identity. This screen
        is a placeholder and does not restore production keys.
      </p>
      <label className="mt-5 text-xs font-medium tracking-wide text-subtle">
        Recovery material
        <textarea
          value={value}
          onChange={(event) => onChange(event.target.value)}
          rows={5}
          className="mt-2 w-full resize-none rounded-md border border-line bg-surface px-3 py-3 text-sm text-fg outline-none focus:border-accent"
        />
      </label>
      {error ? <p className="mt-2 text-sm text-danger">{error}</p> : null}
      <button
        type="button"
        onClick={onContinue}
        className="mt-auto h-14 rounded-lg bg-fg text-[15px] font-medium text-bg transition-transform duration-150 active:scale-[0.98]"
      >
        Continue
      </button>
    </div>
  );
}

function HomeScreen({
  tab,
  onTab,
  themeMode,
  onTheme,
  onAbout,
  onSupport,
  identity,
}: {
  tab: Tab;
  onTab: (tab: Tab) => void;
  themeMode: ThemeMode;
  onTheme: (mode: ThemeMode) => void;
  onAbout: () => void;
  onSupport: () => void;
  identity: Identity | null;
}) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1">
        {tab === "chats" && (
          <EmptyState
            icon={<MessageSquare className="size-10" strokeWidth={1.5} />}
            title="No conversations yet"
            body="When you start a private conversation, it will appear here."
          />
        )}
        {tab === "contacts" && (
          <EmptyState
            icon={<Users className="size-10" strokeWidth={1.5} />}
            title="No contacts yet"
            body="People you trust will be listed here."
          />
        )}
        {tab === "settings" && (
          <SettingsPane
            themeMode={themeMode}
            onTheme={onTheme}
            onAbout={onAbout}
            onSupport={onSupport}
            identity={identity}
          />
        )}
      </div>
      <nav className="grid grid-cols-3 border-t border-line bg-surface px-2 pt-2 pb-[max(12px,env(safe-area-inset-bottom))]">
        <TabButton
          label="Chats"
          active={tab === "chats"}
          onClick={() => onTab("chats")}
          icon={<MessageSquare className="size-5" strokeWidth={1.75} />}
        />
        <TabButton
          label="Contacts"
          active={tab === "contacts"}
          onClick={() => onTab("contacts")}
          icon={<Users className="size-5" strokeWidth={1.75} />}
        />
        <TabButton
          label="Settings"
          active={tab === "settings"}
          onClick={() => onTab("settings")}
          icon={<Settings className="size-5" strokeWidth={1.75} />}
        />
      </nav>
    </div>
  );
}

function SettingsPane({
  themeMode,
  onTheme,
  onAbout,
  onSupport,
  identity,
}: {
  themeMode: ThemeMode;
  onTheme: (mode: ThemeMode) => void;
  onAbout: () => void;
  onSupport: () => void;
  identity: Identity | null;
}) {
  const modes: { id: ThemeMode; label: string }[] = [
    { id: "system", label: "System" },
    { id: "dark", label: "Dark" },
    { id: "light", label: "Light" },
  ];
  return (
    <div className="h-full overflow-auto px-5 pt-3 pb-6">
      <h2 className="font-display text-[22px] font-semibold text-fg">Settings</h2>
      {identity ? (
        <p className="mt-2 font-mono text-xs text-muted">{identity.publicLabel}</p>
      ) : null}
      <p className="mt-6 text-xs font-medium tracking-wide text-subtle uppercase">
        Appearance
      </p>
      <div className="mt-2">
        {modes.map((mode) => (
          <label
            key={mode.id}
            className="flex min-h-11 items-center gap-3 py-2 text-[15px] text-fg"
          >
            <input
              type="radio"
              name="theme"
              checked={themeMode === mode.id}
              onChange={() => onTheme(mode.id)}
              className="size-4 accent-accent"
            />
            {mode.label}
          </label>
        ))}
      </div>
      <div className="mt-4 divide-y divide-line border-y border-line">
        <button
          type="button"
          onClick={onAbout}
          className="flex min-h-14 w-full items-center justify-between text-left text-[15px] text-fg"
        >
          About ArmorChat
          <ChevronRight className="size-4 text-subtle" />
        </button>
        <button
          type="button"
          onClick={onSupport}
          className="flex min-h-14 w-full items-center justify-between text-left text-[15px] text-fg"
        >
          Support ArmorChat
          <ChevronRight className="size-4 text-subtle" />
        </button>
      </div>
      <p className="mt-6 text-xs tracking-wide text-subtle">Secured by Veltrix</p>
    </div>
  );
}

function AboutScreen({ onBack }: { onBack: () => void }) {
  return (
    <div className="flex flex-1 flex-col px-6 pb-8">
      <BackBar title="About ArmorChat" onBack={onBack} />
      <Mark size={96} />
      <h2 className="font-display mt-5 text-[22px] font-semibold text-fg">ArmorChat</h2>
      <p className="mt-2 text-[15px] text-muted">Version 0.1.0-foundation</p>
      <p className="mt-5 text-[15px] text-fg">Developed by CodeYantra Technologies</p>
      <p className="mt-2 text-[15px] text-muted">Secured by Veltrix</p>
    </div>
  );
}

function SupportScreen({ onBack }: { onBack: () => void }) {
  return (
    <div className="flex flex-1 flex-col px-6 pb-8">
      <BackBar title="Support ArmorChat" onBack={onBack} />
      <p className="mt-2 text-[15px] leading-relaxed text-muted">
        A way to support development will be added here. No payments are
        collected in this version.
      </p>
      <p className="mt-6 text-xs tracking-wide text-subtle">
        by CodeYantra Technologies
      </p>
    </div>
  );
}

function EmptyState({
  icon,
  title,
  body,
}: {
  icon: ReactNode;
  title: string;
  body: string;
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-8 text-center">
      <div className="text-muted">{icon}</div>
      <h2 className="font-display mt-4 text-[20px] font-semibold text-fg">{title}</h2>
      <p className="mt-2 max-w-[260px] text-sm leading-relaxed text-muted">{body}</p>
    </div>
  );
}

function TabButton({
  label,
  active,
  onClick,
  icon,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
  icon: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex min-h-11 flex-col items-center gap-1 text-[11px] font-medium ${
        active ? "text-fg" : "text-subtle"
      }`}
    >
      {icon}
      {label}
    </button>
  );
}

function BackBar({ title, onBack }: { title: string; onBack: () => void }) {
  return (
    <div className="flex min-h-12 items-center gap-1 py-1">
      <button
        type="button"
        onClick={onBack}
        className="flex size-11 items-center justify-center rounded-md text-fg"
        aria-label="Back"
      >
        <ChevronLeft className="size-5" />
      </button>
      <h1 className="font-display text-[17px] font-semibold text-fg">{title}</h1>
    </div>
  );
}

function Mark({ size }: { size: number }) {
  return (
    <img
      src="/brand/armorchat-mark.png"
      alt="ArmorChat mark"
      width={size}
      height={size}
      className="max-w-none select-none"
      style={{ width: size, height: size }}
    />
  );
}
