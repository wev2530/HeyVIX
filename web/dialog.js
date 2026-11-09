// A small pop-up box, like the dialogs in the Android app.
//
// showDialog({ title, message, content, buttons, cancelable })
//   title      text at the top
//   message    plain text under the title (optional)
//   content    a DOM element to show under the message, for example a form (optional)
//   buttons    [{ label, value, primary, danger }] left to right. The value is what
//              the returned promise gives back.
//   cancelable true (default): tapping outside the box or pressing Escape closes it
//              and gives back null. false: only the buttons close it.
export function showDialog({ title, message, content, buttons, cancelable = true }) {
  return new Promise((resolve) => {
    const scrim = document.createElement("div");
    scrim.className = "modal-scrim";

    const card = document.createElement("div");
    card.className = "modal-card";
    card.setAttribute("role", "dialog");
    card.setAttribute("aria-modal", "true");

    if (title) {
      const h = document.createElement("div");
      h.className = "modal-title";
      h.textContent = title;
      card.appendChild(h);
    }

    const body = document.createElement("div");
    body.className = "modal-body";
    if (message) {
      const p = document.createElement("div");
      p.className = "modal-message";
      p.textContent = message;
      body.appendChild(p);
    }
    if (content) body.appendChild(content);
    card.appendChild(body);

    const row = document.createElement("div");
    row.className = "modal-buttons";

    let done = false;
    const close = (value) => {
      if (done) return;
      done = true;
      document.removeEventListener("keydown", onKey, true);
      scrim.remove();
      resolve(value);
    };

    for (const b of buttons || [{ label: "OK", value: true, primary: true }]) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className =
        "modal-btn" + (b.primary ? " primary" : "") + (b.danger ? " danger" : "");
      btn.textContent = b.label;
      btn.onclick = () => close(b.value);
      row.appendChild(btn);
    }
    card.appendChild(row);

    const onKey = (e) => {
      if (e.key === "Escape" && cancelable) {
        e.preventDefault();
        close(null);
      }
    };
    document.addEventListener("keydown", onKey, true);

    scrim.addEventListener("click", (e) => {
      if (e.target === scrim && cancelable) close(null);
    });

    scrim.appendChild(card);
    document.body.appendChild(scrim);

    const first = card.querySelector("input, textarea");
    if (first) first.focus();
  });
}
