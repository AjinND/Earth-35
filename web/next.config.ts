import type { NextConfig } from "next";
const config: NextConfig = { output: "standalone", poweredByHeader: false,distDir:process.env.E35_DIST_DIR||".next",allowedDevOrigins:["127.0.0.1"] };
export default config;
