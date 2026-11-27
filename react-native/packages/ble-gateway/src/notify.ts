/**
 * Calls an app's listener. An error it throws surfaces on its own, without interrupting the caller or the
 * listeners after it.
 */
export function notify<T>(listener: (value: T) => void, value: T): void {
  try {
    listener(value);
  } catch (error) {
    setTimeout(() => { throw error; }, 0);
  }
}
