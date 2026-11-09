import { showDialog } from "./dialog.js";

// Where people write to ask for their account and data to be deleted.
export const PRIVACY_CONTACT = "gcvdesign@outlook.com";

// Same wording as the Privacy Notice in the Android app.
// The only change: the web app keeps the user's own AI key in the browser, not on a phone.
const SECTIONS = [
  [
    "What we collect",
    "Your email (or Google account email), your chats and messages, and a daily message count.",
  ],
  [
    "Why",
    "To sign you in, save your chat history on your phone and on the web, and apply a fair daily limit on the shared AI key.",
  ],
  [
    "How it is protected",
    "Your data is stored in Supabase. Each account can only read and change its own chats. Connection tokens are stored encrypted. Your own AI key is saved in this browser; if you use Connections, it is sent to our server only for that request.",
  ],
  [
    "AI providers",
    "Your messages are sent to the AI provider that answers them (the shared service, or the provider whose key you added). Please do not share ID numbers, passwords or other sensitive details in chats.",
  ],
  [
    "Your rights",
    "We do not sell your data. You can delete chats inside the app, and you can ask us to delete your account and all your data at any time: ",
  ],
];

export function showPrivacyNotice() {
  const box = document.createElement("div");
  box.className = "notice";

  SECTIONS.forEach(([heading, text], i) => {
    const h = document.createElement("div");
    h.className = "notice-head";
    h.textContent = heading;
    box.appendChild(h);

    const p = document.createElement("p");
    p.className = "notice-text";
    p.appendChild(document.createTextNode(text));
    if (i === SECTIONS.length - 1) {
      const a = document.createElement("a");
      a.href = "mailto:" + PRIVACY_CONTACT;
      a.textContent = PRIVACY_CONTACT;
      p.appendChild(a);
    }
    box.appendChild(p);
  });

  return showDialog({
    title: "Privacy Notice",
    content: box,
    buttons: [{ label: "OK", value: true, primary: true }],
  });
}
