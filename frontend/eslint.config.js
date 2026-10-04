import { essentials, typescript } from "@haiiro2gou/eslint-config";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import globals from "globals";

export default [
    { ignores: ["dist"] },
    ...essentials,
    ...typescript,
    reactHooks.configs.flat["recommended-latest"],
    reactRefresh.configs.vite,
    {
        files: ["**/*.{ts,tsx}"],
        languageOptions: {
            globals: globals.browser,
            parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
        },
    },
    {
        // React components must be PascalCase for JSX.
        files: ["**/*.tsx"],
        rules: {
            "@typescript-eslint/naming-convention": [
                "warn",
                { selector: "function", format: ["camelCase", "PascalCase"] },
            ],
        },
    },
];
