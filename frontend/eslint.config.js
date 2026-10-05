import js from "@eslint/js";
import globals from "globals";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";
import { globalIgnores } from "eslint/config";
import { essentials, node, typescript, react } from "@haiiro2gou/eslint-config";

export default tseslint.config([
    globalIgnores(["dist", "vite.config.ts", "eslint.config.js"]),
    ...essentials,
    ...node,
    ...typescript,
    ...react,
    {
        files: ["**/*.{ts,tsx}"],
        extends: [js.configs.recommended, reactRefresh.configs.vite],
        languageOptions: {
            ecmaVersion: 2020,
            globals: globals.browser,
            parserOptions: {
                project: ["./tsconfig.json"],
            },
        },
        // ブラウザで動くコードだが node プリセットが組み込み API の対応版を見るので、engines と同じ版にそろえる
        settings: { node: { version: ">=24" } },
    },
    {
        // React のコンポーネントは JSX のために PascalCase にする
        files: ["**/*.tsx"],
        rules: {
            "@typescript-eslint/naming-convention": [
                "warn",
                { selector: ["function", "variable"], format: ["camelCase", "PascalCase", "UPPER_CASE"] },
            ],
        },
    },
]);
