import { useEffect, useState } from "react";

type Status = "loading" | "signed-in" | "signed-out" | "error";

export default function App() {
    const [status, setStatus] = useState<Status>("loading");

    useEffect(() => {
        const check = async () => {
            try {
                const res = await fetch("/api/me");
                if (res.ok) setStatus("signed-in");
                else if (res.status === 401) setStatus("signed-out");
                else setStatus("error");
            } catch {
                setStatus("error");
            }
        };
        void check();
    }, []);

    return (
        <main className="mx-auto max-w-xl p-4">
            <h1 className="text-xl font-bold">Schedule Dashboard</h1>
            {status === "loading" && <p>確認中…</p>}
            {status === "signed-in" && <p>ログイン済みです。</p>}
            {status === "signed-out" && (
                <a className="mt-4 inline-block rounded bg-blue-600 px-4 py-2 text-white" href="/auth/login">
                    Google でログイン
                </a>
            )}
            {status === "error" && <p className="text-red-600">サーバーに接続できません。</p>}
        </main>
    );
}
