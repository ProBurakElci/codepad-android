/*
 * Draws the launcher icon:  node tools/make-icons.js
 *
 * Icons are the one thing in this repo that cannot be a text file, so they are
 * drawn from code instead of being pasted in as binaries nobody can review.
 * Re-run this after changing anything below and the PNGs are rebuilt.
 *
 * Two sets come out:
 *
 *   ic_launcher.png in every mipmap-* folder: square, for Android 7 and 8.0
 *   ic_launcher_round.png beside it: circular, for the launchers that ask
 *
 * Android 8.1 and later use the adaptive icon in mipmap-anydpi-v26 instead,
 * which is vector XML and needs nothing from this script.
 */
"use strict";

const fs = require("fs");
const path = require("path");
const { createCanvas } = require("@napi-rs/canvas");

const res = path.resolve(__dirname, "..", "app", "src", "main", "res");

/* The densities Android asks for, and the pixel size each one wants. */
const DENSITIES = [
  { dir: "mipmap-mdpi", size: 48 },
  { dir: "mipmap-hdpi", size: 72 },
  { dir: "mipmap-xhdpi", size: 96 },
  { dir: "mipmap-xxhdpi", size: 144 },
  { dir: "mipmap-xxxhdpi", size: 192 },
];

const BACKGROUND = "#0b1120";
const BRACKETS = "#38bdf8";
const SLASH = "#f97316";

/**
 * The mark: `</>` on the editor's own background.
 *
 * Everything is a fraction of the size rather than a pixel count, so the 48px
 * icon and the 192px one are the same drawing and not two drawings that
 * happen to look similar.
 */
function draw(size, round) {
  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext("2d");
  const u = size / 100; // one percent, the unit everything below is in

  ctx.save();
  if (round) {
    ctx.beginPath();
    ctx.arc(size / 2, size / 2, size / 2, 0, Math.PI * 2);
    ctx.clip();
  } else {
    // the rounded square Android has used since Oreo
    const r = 22 * u;
    ctx.beginPath();
    ctx.moveTo(r, 0);
    ctx.lineTo(size - r, 0);
    ctx.quadraticCurveTo(size, 0, size, r);
    ctx.lineTo(size, size - r);
    ctx.quadraticCurveTo(size, size, size - r, size);
    ctx.lineTo(r, size);
    ctx.quadraticCurveTo(0, size, 0, size - r);
    ctx.lineTo(0, r);
    ctx.quadraticCurveTo(0, 0, r, 0);
    ctx.clip();
  }

  ctx.fillStyle = BACKGROUND;
  ctx.fillRect(0, 0, size, size);

  /* a faint glow so the mark does not sit on flat black */
  const glow = ctx.createRadialGradient(size * 0.5, size * 0.34, 0, size * 0.5, size * 0.34, size * 0.7);
  glow.addColorStop(0, "rgba(56, 189, 248, 0.20)");
  glow.addColorStop(1, "rgba(56, 189, 248, 0)");
  ctx.fillStyle = glow;
  ctx.fillRect(0, 0, size, size);

  ctx.lineWidth = 8 * u;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";

  /* the two brackets */
  ctx.strokeStyle = BRACKETS;
  ctx.beginPath();
  ctx.moveTo(38 * u, 32 * u);
  ctx.lineTo(20 * u, 50 * u);
  ctx.lineTo(38 * u, 68 * u);
  ctx.stroke();

  ctx.beginPath();
  ctx.moveTo(62 * u, 32 * u);
  ctx.lineTo(80 * u, 50 * u);
  ctx.lineTo(62 * u, 68 * u);
  ctx.stroke();

  /* and the slash between them */
  ctx.strokeStyle = SLASH;
  ctx.beginPath();
  ctx.moveTo(56 * u, 28 * u);
  ctx.lineTo(44 * u, 72 * u);
  ctx.stroke();

  ctx.restore();
  return canvas;
}

let written = 0;
for (const density of DENSITIES) {
  const dir = path.join(res, density.dir);
  fs.mkdirSync(dir, { recursive: true });

  for (const round of [false, true]) {
    const name = round ? "ic_launcher_round.png" : "ic_launcher.png";
    const canvas = draw(density.size, round);
    fs.writeFileSync(path.join(dir, name), canvas.toBuffer("image/png"));
    written++;
  }

  console.log("  " + density.dir + "  " + density.size + "x" + density.size);
}

console.log("\n" + written + " icons drawn.");
