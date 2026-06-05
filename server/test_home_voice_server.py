import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from home_voice_server import ScheduleStore, build_voice_prompt, fallback_structured_command


class ScheduleStoreTest(unittest.TestCase):
    def make_store(self):
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        return ScheduleStore(Path(temp_dir.name) / "schedule.json")

    def test_proposal_accept_adds_task(self):
        store = self.make_store()
        response = store.handle_ai_command({"action": "propose_add_task", "title": "Buy milk"})
        proposal_id = response["schedule"]["proposals"][0]["proposal_id"]

        accepted = store.accept_proposal(proposal_id)

        self.assertEqual("accepted", accepted["status"])
        self.assertEqual([], accepted["schedule"]["proposals"])
        self.assertEqual("Buy milk", accepted["schedule"]["tasks"][0]["title"])

    def test_reject_removes_proposal_without_applying(self):
        store = self.make_store()
        response = store.handle_ai_command({"action": "propose_add_event", "title": "Meeting"})
        proposal_id = response["schedule"]["proposals"][0]["proposal_id"]

        rejected = store.reject_proposal(proposal_id)

        self.assertEqual("rejected", rejected["status"])
        self.assertEqual([], rejected["schedule"]["proposals"])
        self.assertEqual([], rejected["schedule"]["calendars"]["calendar_1"])

    def test_delete_task_removes_instead_of_marking_done(self):
        store = self.make_store()
        added = store.add_task({"title": "Wash dishes"})
        task_id = added["schedule"]["tasks"][0]["id"]

        deleted = store.delete_task(task_id)

        self.assertTrue(deleted["applied"])
        self.assertEqual([], deleted["schedule"]["tasks"])

    def test_keeps_events_from_last_two_weeks(self):
        store = self.make_store()
        old = date.today() - timedelta(days=15)
        recent = date.today() - timedelta(days=14)

        store.add_event({"title": "Old", "date": old.isoformat()})
        store.add_event({"title": "Recent", "date": recent.isoformat()})
        snapshot = store.snapshot()

        titles = [event["title"] for event in snapshot["calendars"]["calendar_1"]]
        self.assertNotIn("Old", titles)
        self.assertIn("Recent", titles)

    def test_fallback_treats_undated_problem_solving_as_task(self):
        command = fallback_structured_command("선형대수 문제풀이")

        self.assertEqual("propose_add_task", command["action"])
        self.assertEqual("선형대수 문제풀이", command["title"])

    def test_fallback_treats_dated_problem_solving_as_calendar_event(self):
        command = fallback_structured_command("다음주 월요일 선형대수 문제풀이")

        self.assertEqual("propose_add_event", command["action"])
        self.assertEqual("다음주 월요일 선형대수 문제풀이", command["title"])
        self.assertEqual("00:00", command["start_time"])

    def test_prompt_tells_ai_not_to_ask_dates_for_tasks(self):
        prompt = build_voice_prompt("선형대수 문제풀이", {"calendars": {}, "tasks": []})

        self.assertIn("Use propose_add_task for non-calendar work items", prompt)
        self.assertIn("Do not ask when to schedule it", prompt)
        self.assertIn("set start_time to \"00:00\"", prompt)
        self.assertIn("선형대수 문제풀이", prompt)

    def test_ai_context_limits_chat_history_to_recent_ten(self):
        store = self.make_store()
        for i in range(12):
            store.handle_ai_command(
                {"action": "message", "message": f"reply-{i}"},
                user_text=f"user-{i}",
            )

        full_history = store.snapshot()["chat_history"]
        ai_history = store.ai_context()["chat_history"]

        self.assertGreater(len(full_history), 10)
        self.assertEqual(10, len(ai_history))
        self.assertEqual(full_history[-10:], ai_history)


if __name__ == "__main__":
    unittest.main()
