import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// En desarrollo (npm run dev) la API la sirve el backend Java en :8080.
// En producción el resultado se copia dentro del jar (target/classes/public).
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://localhost:8080", "/ficha": "http://localhost:8080", "/norma": "http://localhost:8080" } },
  build: { outDir: "../target/classes/public", emptyOutDir: true },
});
