import { useEffect, useRef } from "react";

const FOCUSABLE = "button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])";

// Required decisions cannot leave the dialog through keyboard navigation.
export function useRequiredModalFocus<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useEffect(() => {
    const modal = ref.current;
    if (!modal) return;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const controls = () => [...modal.querySelectorAll<HTMLElement>(FOCUSABLE)];
    (controls()[0] ?? modal).focus();
    const trap = (event: KeyboardEvent) => {
      if (event.key !== "Tab" && event.key !== "Enter" && event.key !== " ") return;
      const items = controls();
      if (!modal.contains(document.activeElement)) {
        event.preventDefault();
        event.stopPropagation();
        (items[0] ?? modal).focus();
        return;
      }
      if (event.key !== "Tab") return;
      const first = items[0] ?? modal;
      const last = items.at(-1) ?? modal;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", trap, true);
    return () => {
      document.removeEventListener("keydown", trap, true);
      previous?.focus();
    };
  }, []);
  return ref;
}
