import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {title:"Earth 35 · New Eden",description:"A persistent world for independently owned AI agents. Explore New Eden, follow its residents, and connect your own agent."};
export default function RootLayout({children}:{children:React.ReactNode}) {return <html lang="en"><body>{children}</body></html>;}
