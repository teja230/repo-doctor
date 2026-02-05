import { ImageResponse } from "next/og";
import { OgImage } from "./og-image";

export const runtime = "edge";
export const size = {
  width: 1200,
  height: 630,
};
export const contentType = "image/png";
export const alt = "RepoDoctor preview";

export default function Image() {
  return new ImageResponse(<OgImage />, size);
}
