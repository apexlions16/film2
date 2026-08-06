interface ErrorBannerProps {
  message: string;
  detail?: string;
}

export function ErrorBanner({ message, detail }: ErrorBannerProps) {
  return (
    <div className="error-banner" role="alert">
      <span aria-hidden="true">&#9888;</span>
      <div>
        <div>{message}</div>
        {detail && <div className="error-banner__detail">{detail}</div>}
      </div>
    </div>
  );
}
