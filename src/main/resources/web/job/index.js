document.addEventListener("DOMContentLoaded", function () {
    function timeAgo(timestamp) {
        const now = new Date();
        const past = new Date(timestamp);
        const diffInSeconds = Math.floor((now - past) / 1000);

        if (diffInSeconds < 60) {
            return `${diffInSeconds} seconds ago`;
        }
        const diffInMinutes = Math.floor(diffInSeconds / 60);
        if (diffInMinutes < 60) {
            return `${diffInMinutes} minutes ago`;
        }
        const diffInHours = Math.floor(diffInMinutes / 60);
        if (diffInHours < 24) {
            return `${diffInHours} hours ago`;
        }
        const diffInDays = Math.floor(diffInHours / 24);
        return `${diffInDays} days ago`;
    }

    function updateTimeAgo() {
        const timeElement = document.getElementById("timestamp");
        if (!timeElement) return;
        const datetime = timeElement.getAttribute("datetime");
        if (!datetime) return;
        timeElement.textContent = timeAgo(datetime);
    }

    updateTimeAgo();
    setInterval(updateTimeAgo, 1000);
});
