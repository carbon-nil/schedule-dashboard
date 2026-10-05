import * as React from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App";
import { SharePage } from "./SharePage";

// ルーターは入れない。公開ページ /share/:token だけを分ける
const shareToken = /^\/share\/([^/]+)$/.exec(window.location.pathname)?.[1];

createRoot(document.getElementById("root")!).render(
    <React.StrictMode>{shareToken === undefined ? <App /> : <SharePage token={shareToken} />}</React.StrictMode>,
);
