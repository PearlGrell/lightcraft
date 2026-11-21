//! The models on real pictures, when they are downloaded: `LIGHTCRAFT_AI_MODELS` names a folder
//! holding their files (as `lightcraft_ai::models` names them). Without it these tests skip.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use lightcraft_ai::select::{Selector, Tap};
use lightcraft_ai::{Rgb8, Status, Store, depth, detect, inpaint, matte, models};

fn store(id: &str) -> Option<Store> {
    let Some(dir) = std::env::var_os("LIGHTCRAFT_AI_MODELS") else {
        lightcraft_testkit::skip!(Model, "LIGHTCRAFT_AI_MODELS is not set");
        return None;
    };
    let s = Store::new(dir);
    if s.status(id).ok() != Some(Status::Ready) {
        lightcraft_testkit::skip!(Model, "{id} is not in LIGHTCRAFT_AI_MODELS");
        return None;
    }
    Some(s)
}

/// A dark, slightly textured background with a bright disc on the left third.
fn scene(w: usize, h: usize) -> Rgb8 {
    let mut d = Vec::with_capacity(w * h * 3);
    for y in 0..h {
        for x in 0..w {
            let (dx, dy) = (x as f32 - w as f32 * 0.3, y as f32 - h as f32 * 0.5);
            let inside = dx * dx + dy * dy < (h as f32 * 0.2).powi(2);
            let t = ((x * 7 + y * 3) % 23) as u8;
            d.extend(if inside { [230, 60, 40] } else { [30 + t, 70 + t, 40 + t] });
        }
    }
    Rgb8::new(w, h, d).unwrap()
}

#[test]
fn a_tap_on_the_disc_selects_the_disc() {
    let Some(s) = store(models::SELECT) else { return };
    let img = scene(640, 480);
    let sel = Selector::encode(&s, &img, None).unwrap();
    let r = sel.select(&s, &[Tap { x: 0.3, y: 0.5, include: true }]).unwrap();
    let at = |u: f32, v: f32| r.logits[((v * r.side as f32) as usize).min(r.side - 1) * r.side + ((u * r.side as f32) as usize).min(r.side - 1)];
    assert!(at(0.3, 0.5) > 0.0, "the disc's centre is selected");
    assert!(at(0.85, 0.15) < 0.0 && at(0.85, 0.85) < 0.0, "the far background is not");
}

#[test]
fn a_filled_hole_takes_the_colour_of_its_surroundings() {
    let Some(s) = store(models::INPAINT) else { return };
    let img = scene(512, 384);
    // the hole: a square on the right, in the textured background
    let hole: Vec<bool> = (0..512 * 384)
        .map(|i| {
            let (x, y) = (i % 512, i / 512);
            (360..420).contains(&x) && (160..220).contains(&y)
        })
        .collect();
    let mut punched = img.clone();
    for (i, h) in hole.iter().enumerate() {
        if *h {
            punched.data[i * 3..i * 3 + 3].copy_from_slice(&[255, 255, 255]);
        }
    }
    let out = inpaint::fill(&s, &punched, &hole, None).unwrap();
    let centre = (190 * 512 + 390) * 3;
    let px = &out.data[centre..centre + 3];
    // greenish background, not the white the hole was punched with nor the red disc
    assert!(px[1] > px[0] && px[0] < 120, "{px:?}");
    // outside the hole nothing changed
    assert_eq!(&out.data[..300], &punched.data[..300]);
}

#[test]
fn detection_runs_and_boxes_stay_on_the_photo() {
    let Some(s) = store(models::DETECT) else { return };
    let found = detect::detect(&s, &scene(640, 480), 0.0).unwrap();
    assert!(!found.is_empty());
    assert!(found.iter().all(|d| d.x0 <= d.x1 && d.y0 <= d.y1 && d.x1 <= 1.0 && d.y1 <= 1.0));
    assert!(found.windows(2).all(|w| w[0].score >= w[1].score), "best first");
}

#[test]
fn depth_puts_the_floor_near_and_the_sky_far() {
    let Some(s) = store(models::DEPTH) else { return };
    // a sky over a ground plane whose texture grows coarser toward the bottom (nearer)
    let (w, h) = (640usize, 480usize);
    let mut d = Vec::with_capacity(w * h * 3);
    for y in 0..h {
        for x in 0..w {
            if y < h / 2 {
                d.extend([120u8, 170, 230]);
            } else {
                let k = 2 + (y - h / 2) / 12;
                let v = if ((x / k) + (y / k)) % 2 == 0 { 90u8 } else { 140 };
                d.extend([v / 2 + 20, v, v / 3]);
            }
        }
    }
    let g = depth::estimate(&s, &Rgb8::new(w, h, d).unwrap(), 64, None).unwrap();
    let at = |u: f32, v: f32| g[(v * 64.0) as usize * 64 + (u * 64.0) as usize];
    assert!(at(0.5, 0.95) > at(0.5, 0.1), "the bottom of the ground is nearer than the sky");
}

#[test]
fn the_best_fill_also_takes_the_colour_of_its_surroundings() {
    let Some(s) = store(models::INPAINT_BEST) else { return };
    let img = scene(512, 384);
    let hole: Vec<bool> = (0..512 * 384)
        .map(|i| {
            let (x, y) = (i % 512, i / 512);
            (360..420).contains(&x) && (160..220).contains(&y)
        })
        .collect();
    let mut punched = img.clone();
    for (i, h) in hole.iter().enumerate() {
        if *h {
            punched.data[i * 3..i * 3 + 3].copy_from_slice(&[255, 255, 255]);
        }
    }
    let out = inpaint::fill_best(&s, &punched, &hole, None).unwrap();
    let centre = (190 * 512 + 390) * 3;
    let px = &out.data[centre..centre + 3];
    assert!(px[1] > px[0] && px[0] < 120, "{px:?}");
}

#[test]
fn a_matte_is_one_value_per_pixel_in_range() {
    let Some(s) = store(models::MATTE) else { return };
    let img = scene(300, 400);
    let m = matte::matte(&s, &img, None).unwrap();
    assert_eq!(m.len(), 300 * 400);
    assert!(m.iter().all(|v| (0.0..=1.0).contains(v)));
}
